import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    private static final DataStore STORE = new DataStore();
    private static final EventBus EVENTS = new EventBus();

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8080), 0);

        server.createContext("/api/signup", new SignupHandler());
        server.createContext("/api/login", new LoginHandler());
        server.createContext("/api/student-reset-password", new StudentResetPasswordHandler());
        server.createContext("/api/leave", new LeaveHandler());
        server.createContext("/api/my-leaves", new MyLeavesHandler());
        server.createContext("/api/room-vacancies", new RoomVacanciesHandler());
        server.createContext("/api/room-structure", new RoomStructureHandler());
        server.createContext("/api/room-details", new RoomDetailsHandler());
        server.createContext("/api/book-room", new BookRoomHandler());
        server.createContext("/api/student-booking", new StudentBookingHandler());
        server.createContext("/api/check-fee", new CheckFeeHandler());
        server.createContext("/api/dashboard-summary", new DashboardSummaryHandler());
        server.createContext("/api/payment", new PaymentHandler());
        server.createContext("/api/events", new EventsHandler());
        server.createContext("/api/warden-login", new WardenLoginHandler());
        server.createContext("/api/warden-forgot-password", new WardenForgotPasswordHandler());
        server.createContext("/api/warden-leave-queue", new WardenLeaveQueueHandler());
        server.createContext("/api/warden-leave-action", new WardenLeaveActionHandler());

        Path frontendRoot = resolveFrontendRoot();
        server.createContext("/", new StaticHandler(frontendRoot));

        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("Backend running at http://localhost:8080");
        System.out.println("Serving frontend from " + frontendRoot.toAbsolutePath());
    }

    private static Path resolveFrontendRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path direct = cwd.resolve("frontend");
        if (Files.exists(direct)) {
            return direct;
        }
        Path parent = cwd.resolve("..").resolve("frontend").normalize();
        if (Files.exists(parent)) {
            return parent;
        }
        return direct;
    }

    private static class BaseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                addCors(exchange.getResponseHeaders());
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            handleInternal(exchange);
        }

        protected void handleInternal(HttpExchange exchange) throws IOException {
            sendJson(exchange, 405, "{\"success\":false,\"message\":\"Method not allowed\"}");
        }
    }

    private static class SignupHandler extends BaseHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"success\":false,\"message\":\"Use POST\"}");
                return;
            }
            Map<String, String> form = readForm(exchange);
            String regNo = trim(form.get("reg_no"));
            String name = trim(form.get("name"));
            String email = trim(form.get("email")).toLowerCase(Locale.ROOT);
            String dob = trim(form.get("dob"));
            String password = trim(form.get("password"));

            if (regNo.isEmpty() || name.isEmpty() || email.isEmpty() || dob.isEmpty() || password.isEmpty()) {
                sendJson(exchange, 400, "{\"success\":false,\"message\":\"All fields are required\"}");
                return;
            }

            String message = STORE.signup(regNo, name, email, dob, password);
            boolean success = message.startsWith("OK:");
            String payload = success
                    ? "{\"success\":true,\"message\":\"Signup successful\"}"
                    : "{\"success\":false,\"message\":\"" + jsonEscape(message) + "\"}";
            sendJson(exchange, success ? 200 : 400, payload);
        }
    }

    private static class LoginHandler extends BaseHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"success\":false,\"message\":\"Use POST\"}");
                return;
            }
            Map<String, String> form = readForm(exchange);
            String email = trim(form.get("email"));
            String password = trim(form.get("password"));
            Student student = STORE.login(email, password);
            if (student == null) {
                sendJson(exchange, 401, "{\"success\":false,\"message\":\"Invalid credentials. Use registered email/registration number and password.\"}");
                return;
            }
            String payload = "{\"success\":true,\"regNo\":\"" + jsonEscape(student.regNo) + "\",\"name\":\"" + jsonEscape(student.name) + "\"}";
            sendJson(exchange, 200, payload);
        }
    }

    private static class StudentResetPasswordHandler extends BaseHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"success\":false,\"message\":\"Use POST\"}");
                return;
            }
            Map<String, String> form = readForm(exchange);
            String regNo = trim(form.get("reg_no"));
            String dob = trim(form.get("dob"));
            String newPassword = trim(form.get("new_password"));
            if (regNo.isEmpty() || dob.isEmpty() || newPassword.isEmpty()) {
                sendJson(exchange, 400, "{\"success\":false,\"message\":\"Registration number, date of birth, and new password are required\"}");
                return;
            }

            String message = STORE.resetStudentPassword(regNo, dob, newPassword);
            boolean success = message.startsWith("OK:");
            String payload = success
                    ? "{\"success\":true,\"message\":\"Password reset successful\"}"
                    : "{\"success\":false,\"message\":\"" + jsonEscape(message) + "\"}";
            sendJson(exchange, success ? 200 : 400, payload);
        }
    }

    private static class LeaveHandler extends BaseHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"success\":false,\"message\":\"Use POST\"}");
                return;
            }
            Map<String, String> form = readForm(exchange);
            String regNo = trim(form.get("reg_no"));
            String name = trim(form.get("name"));
            String contact = trim(form.get("contact"));
            String purpose = trim(form.get("purpose"));
            String leaveDate = trim(form.get("leave_date"));
            String returnDate = trim(form.get("return_date"));

            if (regNo.isEmpty() || name.isEmpty() || contact.isEmpty() || leaveDate.isEmpty() || returnDate.isEmpty()) {
                sendJson(exchange, 400, "{\"success\":false,\"message\":\"All required fields must be filled\"}");
                return;
            }

            STORE.addLeave(regNo, name, contact, purpose, leaveDate, returnDate);
            publishLeaveUpdated(regNo, "SUBMITTED");
            sendJson(exchange, 200, "{\"success\":true,\"message\":\"Leave request submitted\"}");
        }
    }

    private static class MyLeavesHandler extends BaseHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            String regNo = trim(query.get("reg_no"));
            List<LeaveRequest> leaves = STORE.getLeaves(regNo);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"success\":true,\"records\":[");
            for (int i = 0; i < leaves.size(); i++) {
                LeaveRequest r = leaves.get(i);
                if (i > 0) sb.append(",");
                sb.append("{\"id\":").append(r.id)
                        .append(",\"regNo\":\"").append(jsonEscape(r.regNo)).append("\"")
                        .append(",\"name\":\"").append(jsonEscape(r.name)).append("\"")
                        .append(",\"contact\":\"").append(jsonEscape(r.contact)).append("\"")
                        .append(",\"purpose\":\"").append(jsonEscape(r.purpose)).append("\"")
                        .append(",\"leaveDate\":\"").append(jsonEscape(r.leaveDate)).append("\"")
                        .append(",\"returnDate\":\"").append(jsonEscape(r.returnDate)).append("\"")
                        .append(",\"status\":\"").append(jsonEscape(r.status)).append("\"")
                        .append(",\"reviewedBy\":\"").append(jsonEscape(r.reviewedBy)).append("\"")
                        .append(",\"createdAt\":\"").append(jsonEscape(r.createdAt)).append("\"")
                        .append("}");
            }
            sb.append("]}");
            sendJson(exchange, 200, sb.toString());
        }
    }

    private static class RoomVacanciesHandler extends BaseHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            String bookingMonth = trim(query.get("booking_month"));
            List<Vacancy> vacancies = STORE.getVacancies(bookingMonth);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"success\":true,\"vacancies\":[");
            for (int i = 0; i < vacancies.size(); i++) {
                Vacancy v = vacancies.get(i);
                if (i > 0) sb.append(",");
                sb.append("{\"type\":\"").append(jsonEscape(v.roomType)).append("\","
                        + "\"availableSlots\":").append(v.availableSlots).append(","
                        + "\"totalSlots\":").append(v.totalSlots).append("}");
            }
            sb.append("]}");
            sendJson(exchange, 200, sb.toString());
        }
    }

    private static class RoomStructureHandler extends BaseHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            String roomType = trim(query.get("room_type"));
            int members = parseInt(query.get("members"), 4);
            String bookingMonth = trim(query.get("booking_month"));

            List<Room> rooms = STORE.getRoomStructure(roomType, members, bookingMonth);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"success\":true,\"rooms\":[");
            for (int i = 0; i < rooms.size(); i++) {
                Room r = rooms.get(i);
                if (i > 0) sb.append(",");
                sb.append("{\"roomNo\":\"").append(jsonEscape(r.roomNo)).append("\","
                        + "\"floor\":\"").append(jsonEscape(r.floor)).append("\","
                        + "\"capacity\":").append(r.capacity).append(","
                        + "\"occupied\":").append(r.occupied).append(","
                        + "\"locked\":").append(r.locked).append("}");
            }
            sb.append("]}");
            sendJson(exchange, 200, sb.toString());
        }
    }

    private static class RoomDetailsHandler extends BaseHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            String roomType = trim(query.get("room_type"));
            int members = parseInt(query.get("members"), 4);
            String bookingMonth = trim(query.get("booking_month"));
            String roomNo = trim(query.get("room_no"));

            RoomDetails details = STORE.getRoomDetails(roomType, members, bookingMonth, roomNo);
            if (details == null) {
                sendJson(exchange, 404, "{\"success\":false,\"message\":\"Room not found\"}");
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\"success\":true,\"room\":{")
                    .append("\"roomNo\":\"").append(jsonEscape(details.room.roomNo)).append("\","
                            + "\"floor\":\"").append(jsonEscape(details.room.floor)).append("\","
                            + "\"capacity\":").append(details.room.capacity).append(","
                            + "\"occupied\":").append(details.room.occupied).append(","
                            + "\"locked\":").append(details.room.locked)
                    .append("},\"occupants\":[");
            for (int i = 0; i < details.occupants.size(); i++) {
                Occupant o = details.occupants.get(i);
                if (i > 0) sb.append(",");
                sb.append("{\"regNo\":\"").append(jsonEscape(o.regNo)).append("\","
                        + "\"name\":\"").append(jsonEscape(o.name)).append("\"}");
            }
            sb.append("]}");
            sendJson(exchange, 200, sb.toString());
        }
    }

    private static class BookRoomHandler extends BaseHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"success\":false,\"message\":\"Use POST\"}");
                return;
            }
            Map<String, String> form = readForm(exchange);
            String regNo = trim(form.get("reg_no"));
            String roomType = trim(form.get("room_type"));
            int members = parseInt(form.get("members"), 4);
            String bookingMonth = trim(form.get("booking_month"));
            String preferredRoom = trim(form.get("preferred_room_no"));

            BookingResult result = STORE.bookRoom(regNo, roomType, members, bookingMonth, preferredRoom);
            if (!result.success) {
                sendJson(exchange, 400, "{\"success\":false,\"message\":\"" + jsonEscape(result.message) + "\"}");
                return;
            }

            Booking b = result.booking;
            String payload = "{\"success\":true,\"message\":\"" + jsonEscape(result.message) + "\",\"booking\":{"
                    + "\"roomType\":\"" + jsonEscape(b.roomType) + "\","
                    + "\"floor\":\"" + jsonEscape(b.floor) + "\","
                    + "\"roomNo\":\"" + jsonEscape(b.roomNo) + "\","
                    + "\"fee\":" + b.fee + ","
                    + "\"paymentStatus\":\"" + jsonEscape(b.paymentStatus) + "\"}}";
            publishBookingUpdated(b.regNo, b.bookingMonth);
            sendJson(exchange, 200, payload);
        }
    }

    private static class StudentBookingHandler extends BaseHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            String regNo = trim(query.get("reg_no"));
            String bookingMonth = trim(query.get("booking_month"));
            Booking booking = bookingMonth.isEmpty()
                    ? STORE.getLatestBooking(regNo)
                    : STORE.getBooking(regNo, bookingMonth);
            if (booking == null) {
                sendJson(exchange, 404, "{\"success\":false,\"message\":\"Booking not found\"}");
                return;
            }
            String payload = "{\"success\":true,\"booking\":{"
                    + "\"roomType\":\"" + jsonEscape(booking.roomType) + "\","
                    + "\"floor\":\"" + jsonEscape(booking.floor) + "\","
                    + "\"roomNo\":\"" + jsonEscape(booking.roomNo) + "\","
                    + "\"fee\":" + booking.fee + ","
                    + "\"paymentStatus\":\"" + jsonEscape(booking.paymentStatus) + "\"}}";
            sendJson(exchange, 200, payload);
        }
    }

    private static class CheckFeeHandler extends BaseHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            String regNo = trim(query.get("reg_no"));
            Booking booking = STORE.getLatestBooking(regNo);
            if (booking == null) {
                sendJson(exchange, 404, "{\"success\":false,\"message\":\"No booking found for this student\"}");
                return;
            }
            boolean paid = "PAID".equalsIgnoreCase(booking.paymentStatus);
            int due = paid ? 0 : booking.fee;
            String payload = "{\"success\":true,\"paid\":" + paid + ",\"dueAmount\":" + due + ",\"booking\":{"
                    + "\"roomType\":\"" + jsonEscape(booking.roomType) + "\","
                    + "\"floor\":\"" + jsonEscape(booking.floor) + "\","
                    + "\"roomNo\":\"" + jsonEscape(booking.roomNo) + "\"}}";
            sendJson(exchange, 200, payload);
        }
    }

    private static class DashboardSummaryHandler extends BaseHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            String regNo = trim(query.get("reg_no"));
            Student student = STORE.getStudentByReg(regNo);
            Booking booking = STORE.getLatestBooking(regNo);
            List<DashboardMeal> meals = STORE.getTodayMeals();
            List<DashboardNotice> notices = STORE.getNotices();

            StringBuilder sb = new StringBuilder();
            sb.append("{\"success\":true");
            sb.append(",\"student\":{")
                    .append("\"regNo\":\"").append(jsonEscape(regNo)).append("\",")
                    .append("\"name\":\"").append(jsonEscape(student == null ? "" : student.name)).append("\"")
                    .append("}");

            if (booking == null) {
                sb.append(",\"booking\":null");
                sb.append(",\"fee\":{")
                        .append("\"hasBooking\":false,")
                        .append("\"paid\":false,")
                        .append("\"dueAmount\":0,")
                        .append("\"paidAmount\":0,")
                        .append("\"totalAmount\":0,")
                        .append("\"message\":\"No booking found for this student\"")
                        .append("}");
            } else {
                boolean paid = "PAID".equalsIgnoreCase(booking.paymentStatus);
                int due = paid ? 0 : booking.fee;
                int paidAmount = paid ? booking.fee : 0;
                sb.append(",\"booking\":{")
                        .append("\"roomType\":\"").append(jsonEscape(booking.roomType)).append("\",")
                        .append("\"floor\":\"").append(jsonEscape(booking.floor)).append("\",")
                        .append("\"roomNo\":\"").append(jsonEscape(booking.roomNo)).append("\",")
                        .append("\"members\":").append(booking.members).append(",")
                        .append("\"fee\":").append(booking.fee).append(",")
                        .append("\"paymentStatus\":\"").append(jsonEscape(booking.paymentStatus)).append("\",")
                        .append("\"bookingMonth\":\"").append(jsonEscape(booking.bookingMonth)).append("\"")
                        .append("}");
                sb.append(",\"fee\":{")
                        .append("\"hasBooking\":true,")
                        .append("\"paid\":").append(paid).append(",")
                        .append("\"dueAmount\":").append(due).append(",")
                        .append("\"paidAmount\":").append(paidAmount).append(",")
                        .append("\"totalAmount\":").append(booking.fee).append(",")
                        .append("\"message\":\"").append(jsonEscape(paid ? "No pending fee right now" : "Pending payment for current booking")).append("\"")
                        .append("}");
            }

            sb.append(",\"meals\":[");
            for (int i = 0; i < meals.size(); i++) {
                DashboardMeal meal = meals.get(i);
                if (i > 0) sb.append(",");
                sb.append("{\"label\":\"").append(jsonEscape(meal.label)).append("\",")
                        .append("\"time\":\"").append(jsonEscape(meal.time)).append("\",")
                        .append("\"item\":\"").append(jsonEscape(meal.item)).append("\"}");
            }
            sb.append("]");

            sb.append(",\"notices\":[");
            for (int i = 0; i < notices.size(); i++) {
                DashboardNotice notice = notices.get(i);
                if (i > 0) sb.append(",");
                sb.append("{\"text\":\"").append(jsonEscape(notice.text)).append("\",")
                        .append("\"date\":\"").append(jsonEscape(notice.date)).append("\",")
                        .append("\"source\":\"").append(jsonEscape(notice.source)).append("\"}");
            }
            sb.append("]}");
            sendJson(exchange, 200, sb.toString());
        }
    }

    private static class PaymentHandler extends BaseHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"success\":false,\"message\":\"Use POST\"}");
                return;
            }
            Map<String, String> form = readForm(exchange);
            String regNo = trim(form.get("reg_no"));
            Booking booking = STORE.markLatestBookingPaid(regNo);
            if (booking == null) {
                sendJson(exchange, 404, "{\"success\":false,\"message\":\"No booking found\"}");
                return;
            }
            publishPaymentUpdated(booking.regNo, booking.bookingMonth);
            sendJson(exchange, 200, "{\"success\":true,\"message\":\"Payment marked as PAID\"}");
        }
    }

    private static class WardenLoginHandler extends BaseHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"success\":false,\"message\":\"Use POST\"}");
                return;
            }
            Map<String, String> form = readForm(exchange);
            String wardenNo = trim(form.get("warden_no")).toUpperCase(Locale.ROOT);
            String password = trim(form.get("password"));
            Warden warden = STORE.wardenLogin(wardenNo, password);
            if (warden == null) {
                sendJson(exchange, 401, "{\"success\":false,\"message\":\"Invalid warden credentials\"}");
                return;
            }
            String payload = "{\"success\":true,\"warden\":{"
                    + "\"wardenNo\":\"" + jsonEscape(warden.wardenNo) + "\","
                    + "\"name\":\"" + jsonEscape(warden.name) + "\","
                    + "\"floorNo\":\"" + jsonEscape(warden.floorNo) + "\","
                    + "\"memberGroup\":\"" + jsonEscape(warden.memberGroup) + "\","
                    + "\"roomType\":\"" + jsonEscape(warden.roomType) + "\"}}";
            sendJson(exchange, 200, payload);
        }
    }

    private static class WardenForgotPasswordHandler extends BaseHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"success\":false,\"message\":\"Use POST\"}");
                return;
            }
            Map<String, String> form = readForm(exchange);
            String wardenNo = trim(form.get("warden_no")).toUpperCase(Locale.ROOT);
            Warden warden = STORE.wardens.get(wardenNo);
            if (warden == null) {
                sendJson(exchange, 404, "{\"success\":false,\"message\":\"Warden not found\"}");
                return;
            }
            sendJson(exchange, 200, "{\"success\":true,\"password\":\"" + jsonEscape(warden.password) + "\"}");
        }
    }

    private static class WardenLeaveQueueHandler extends BaseHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            String wardenNo = trim(query.get("warden_no")).toUpperCase(Locale.ROOT);
            Warden warden = STORE.wardens.get(wardenNo);
            if (warden == null) {
                sendJson(exchange, 404, "{\"success\":false,\"message\":\"Warden not found\"}");
                return;
            }
            List<LeaveRequest> pending = STORE.getPendingLeavesForFloor(warden.floorNo);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"success\":true,\"floorNo\":\"").append(jsonEscape(warden.floorNo)).append("\",\"requests\":[");
            for (int i = 0; i < pending.size(); i++) {
                LeaveRequest r = pending.get(i);
                if (i > 0) sb.append(",");
                double attendance = STORE.getAttendancePercent(r.regNo);
                sb.append("{\"id\":").append(r.id)
                        .append(",\"regNo\":\"").append(jsonEscape(r.regNo)).append("\"")
                        .append(",\"name\":\"").append(jsonEscape(r.name)).append("\"")
                        .append(",\"contact\":\"").append(jsonEscape(r.contact)).append("\"")
                        .append(",\"purpose\":\"").append(jsonEscape(r.purpose)).append("\"")
                        .append(",\"leaveDate\":\"").append(jsonEscape(r.leaveDate)).append("\"")
                        .append(",\"returnDate\":\"").append(jsonEscape(r.returnDate)).append("\"")
                        .append(",\"attendancePercent\":").append(String.format(Locale.US, "%.2f", attendance))
                        .append(",\"createdAt\":\"").append(jsonEscape(r.createdAt)).append("\"")
                        .append("}");
            }
            sb.append("]}");
            sendJson(exchange, 200, sb.toString());
        }
    }

    private static class WardenLeaveActionHandler extends BaseHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"success\":false,\"message\":\"Use POST\"}");
                return;
            }
            Map<String, String> form = readForm(exchange);
            String wardenNo = trim(form.get("warden_no")).toUpperCase(Locale.ROOT);
            int leaveId = parseInt(form.get("leave_id"), -1);
            String action = trim(form.get("action")).toUpperCase(Locale.ROOT);

            String message = STORE.updateLeaveStatus(leaveId, wardenNo, action);
            boolean success = message.startsWith("OK:");
            String payload = success
                    ? "{\"success\":true,\"message\":\"" + jsonEscape(message.substring(3)) + "\"}"
                    : "{\"success\":false,\"message\":\"" + jsonEscape(message) + "\"}";
            if (success) {
                String studentRegNo = STORE.getLeaveRegNo(leaveId);
                if (!studentRegNo.isEmpty()) {
                    publishLeaveUpdated(studentRegNo, action);
                }
            }
            sendJson(exchange, success ? 200 : 400, payload);
        }
    }

    private static class EventsHandler extends BaseHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"success\":false,\"message\":\"Use GET\"}");
                return;
            }

            Headers headers = exchange.getResponseHeaders();
            addCors(headers);
            headers.set("Content-Type", "text/event-stream; charset=UTF-8");
            headers.set("Cache-Control", "no-cache");
            headers.set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            OutputStream output = exchange.getResponseBody();
            SseClient client = new SseClient(output);
            EVENTS.add(client);
            client.send("event: connected\ndata: {\"success\":true}\n\n");

            try {
                while (client.isOpen()) {
                    Thread.sleep(20000L);
                    if (!client.send(": ping\n\n")) {
                        break;
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                EVENTS.remove(client);
            }
        }
    }

    private static class StaticHandler implements HttpHandler {
        private final Path webRoot;

        StaticHandler(Path webRoot) {
            this.webRoot = webRoot.toAbsolutePath().normalize();
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                addCors(exchange.getResponseHeaders());
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/login.html";
            }
            Path requested = webRoot.resolve(path.substring(1)).normalize();
            if (!requested.startsWith(webRoot) || !Files.exists(requested) || Files.isDirectory(requested)) {
                sendText(exchange, 404, "404 File Not Found");
                return;
            }

            byte[] bytes = Files.readAllBytes(requested);
            Headers headers = exchange.getResponseHeaders();
            addCors(headers);
            headers.set("Content-Type", contentType(requested));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        addCors(headers);
        headers.set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        addCors(headers);
        headers.set("Content-Type", "text/plain; charset=UTF-8");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void addCors(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void publishBookingUpdated(String regNo, String bookingMonth) {
        String data = "{\"regNo\":\"" + jsonEscape(regNo) + "\",\"bookingMonth\":\"" + jsonEscape(bookingMonth) + "\"}";
        EVENTS.publish("BOOKING_UPDATED", data);
    }

    private static void publishPaymentUpdated(String regNo, String bookingMonth) {
        String data = "{\"regNo\":\"" + jsonEscape(regNo) + "\",\"bookingMonth\":\"" + jsonEscape(bookingMonth) + "\"}";
        EVENTS.publish("PAYMENT_UPDATED", data);
    }

    private static void publishLeaveUpdated(String regNo, String action) {
        String data = "{\"regNo\":\"" + jsonEscape(regNo) + "\",\"action\":\"" + jsonEscape(action) + "\"}";
        EVENTS.publish("LEAVE_UPDATED", data);
    }

    private static Map<String, String> parseQuery(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) return Collections.emptyMap();
        return parseParams(query);
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String body = new String(readAllBytes(exchange.getRequestBody()), StandardCharsets.UTF_8);
        if (body.isEmpty()) return Collections.emptyMap();
        return parseParams(body);
    }

    private static Map<String, String> parseParams(String raw) {
        Map<String, String> map = new HashMap<>();
        String[] pairs = raw.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx < 0) continue;
            String key = urlDecode(pair.substring(0, idx));
            String value = urlDecode(pair.substring(idx + 1));
            map.put(key, value);
        }
        return map;
    }

    private static String urlDecode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) != -1) {
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static int parseInt(String s, int fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=UTF-8";
        if (name.endsWith(".css")) return "text/css; charset=UTF-8";
        if (name.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (name.endsWith(".json")) return "application/json; charset=UTF-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static class DataStore {
        private final Map<String, Student> studentsByReg = new HashMap<>();
        private final Map<String, Student> studentsByEmail = new HashMap<>();
        private final List<Booking> bookings = new ArrayList<>();
        private final Map<String, Warden> wardens = new HashMap<>();
        private final List<LeaveRequest> leaves = new ArrayList<>();
        private final AtomicInteger leaveId = new AtomicInteger(1000);
        private final Path storeFile;

        DataStore() {
            storeFile = resolveStoreFile();
            seedWardens();
            loadState();
        }

        String signup(String regNo, String name, String email, String dob, String password) {
            String reg = trim(regNo);
            String normalizedEmail = trim(email).toLowerCase(Locale.ROOT);
            if (findStudentByReg(reg) != null) {
                return "Registration number already exists";
            }
            if (studentsByEmail.containsKey(normalizedEmail)) {
                return "Email already registered";
            }
            Student s = new Student(reg, name, normalizedEmail, dob, password);
            studentsByReg.put(reg, s);
            studentsByEmail.put(normalizedEmail, s);
            saveState();
            return "OK:created";
        }

        Student login(String emailOrRegNo, String password) {
            String identity = trim(emailOrRegNo);
            Student s = studentsByEmail.get(identity.toLowerCase(Locale.ROOT));
            if (s == null) {
                s = findStudentByReg(identity);
            }
            if (s == null) return null;
            if (!s.password.equals(password)) return null;
            return s;
        }

        Student getStudentByReg(String regNo) {
            if (regNo == null || regNo.isEmpty()) return null;
            return findStudentByReg(regNo);
        }

        String resetStudentPassword(String regNo, String dob, String newPassword) {
            Student s = findStudentByReg(regNo);
            if (s == null) {
                return "Student not found";
            }
            if (!s.dob.equals(dob)) {
                return "Registration number and date of birth do not match";
            }
            s.password = newPassword;
            saveState();
            return "OK:password-reset";
        }

        void addLeave(String regNo, String name, String contact, String purpose, String leaveDate, String returnDate) {
            LeaveRequest r = new LeaveRequest();
            r.id = leaveId.incrementAndGet();
            r.regNo = regNo;
            r.name = name;
            r.contact = contact;
            r.purpose = purpose == null ? "" : purpose;
            r.leaveDate = leaveDate;
            r.returnDate = returnDate;
            r.status = "PENDING";
            r.floorNo = resolveStudentFloor(regNo);
            r.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            leaves.add(0, r);
            saveState();
        }

        List<LeaveRequest> getLeaves(String regNo) {
            if (regNo == null || regNo.isEmpty()) return Collections.emptyList();
            List<LeaveRequest> out = new ArrayList<>();
            for (LeaveRequest r : leaves) {
                if (regNo.equalsIgnoreCase(r.regNo)) {
                    out.add(r);
                }
            }
            return out;
        }

        List<LeaveRequest> getPendingLeaves() {
            List<LeaveRequest> out = new ArrayList<>();
            for (LeaveRequest r : leaves) {
                if ("PENDING".equalsIgnoreCase(r.status)) {
                    out.add(r);
                }
            }
            return out;
        }

        List<LeaveRequest> getPendingLeavesForFloor(String floorNo) {
            String expectedFloor = trim(floorNo);
            List<LeaveRequest> out = new ArrayList<>();
            if (expectedFloor.isEmpty()) return out;
            for (LeaveRequest r : leaves) {
                if (!"PENDING".equalsIgnoreCase(r.status)) continue;
                String studentFloor = resolveLeaveFloor(r);
                if (expectedFloor.equalsIgnoreCase(studentFloor)) {
                    out.add(r);
                }
            }
            return out;
        }

        String updateLeaveStatus(int id, String wardenNo, String action) {
            LeaveRequest target = null;
            for (LeaveRequest r : leaves) {
                if (r.id == id) {
                    target = r;
                    break;
                }
            }
            if (target == null) return "Leave request not found";
            Warden warden = wardens.get(wardenNo);
            if (warden == null) return "Invalid warden";
            String studentFloor = resolveLeaveFloor(target);
            if (studentFloor.isEmpty()) return "Student has no floor allocation";
            if (!studentFloor.equalsIgnoreCase(warden.floorNo)) {
                return "You can review only floor " + warden.floorNo + " requests";
            }
            if ("ACCEPT".equals(action)) {
                target.status = "GRANTED";
            } else if ("REJECT".equals(action)) {
                target.status = "REJECTED";
            } else {
                return "Unknown action";
            }
            target.reviewedBy = wardenNo;
            saveState();
            return "OK:Leave status updated";
        }

        String getLeaveRegNo(int id) {
            for (LeaveRequest r : leaves) {
                if (r.id == id) {
                    return r.regNo == null ? "" : r.regNo;
                }
            }
            return "";
        }

        BookingResult bookRoom(String regNo, String roomType, int members, String bookingMonth, String preferredRoom) {
            if (regNo.isEmpty() || roomType.isEmpty() || bookingMonth.isEmpty() || preferredRoom.isEmpty()) {
                return BookingResult.fail("Missing booking details");
            }

            Room room = getRoomByNo(roomType, members, bookingMonth, preferredRoom);
            if (room == null) {
                return BookingResult.fail("Room not available");
            }
            if (room.locked) {
                return BookingResult.fail("Selected room is already full");
            }

            Booking existing = getBooking(regNo, bookingMonth);
            if (existing != null) {
                existing.roomType = roomType;
                existing.members = members;
                existing.bookingMonth = bookingMonth;
                existing.roomNo = room.roomNo;
                existing.floor = room.floor;
                existing.fee = calculateFee(roomType, members);
                existing.paymentStatus = "PENDING";
                existing.createdAt = System.currentTimeMillis();
                saveState();
                return BookingResult.success("Booking updated", existing);
            }

            Booking b = new Booking();
            b.regNo = regNo;
            b.roomType = roomType;
            b.members = members;
            b.bookingMonth = bookingMonth;
            b.roomNo = room.roomNo;
            b.floor = room.floor;
            b.fee = calculateFee(roomType, members);
            b.paymentStatus = "PENDING";
            b.createdAt = System.currentTimeMillis();
            bookings.add(b);
            saveState();
            return BookingResult.success("Room booked successfully", b);
        }

        Booking getBooking(String regNo, String bookingMonth) {
            for (Booking b : bookings) {
                if (b.regNo.equalsIgnoreCase(regNo) && b.bookingMonth.equalsIgnoreCase(bookingMonth)) {
                    return b;
                }
            }
            return null;
        }

        Booking getLatestBooking(String regNo) {
            if (regNo == null || regNo.isEmpty()) return null;
            return bookings.stream()
                    .filter(b -> b.regNo.equalsIgnoreCase(regNo))
                    .max(Comparator.comparingLong(b -> b.createdAt))
                    .orElse(null);
        }

        String resolveStudentFloor(String regNo) {
            Booking booking = getLatestBooking(regNo);
            return booking == null ? "" : trim(booking.floor);
        }

        String resolveLeaveFloor(LeaveRequest leaveRequest) {
            if (leaveRequest == null) return "";
            String floor = trim(leaveRequest.floorNo);
            if (!floor.isEmpty()) {
                return floor;
            }
            floor = resolveStudentFloor(leaveRequest.regNo);
            if (!floor.isEmpty()) {
                leaveRequest.floorNo = floor;
            }
            return floor;
        }

        Booking markLatestBookingPaid(String regNo) {
            Booking booking = getLatestBooking(regNo);
            if (booking == null) return null;
            booking.paymentStatus = "PAID";
            saveState();
            return booking;
        }

        List<DashboardMeal> getTodayMeals() {
            String day = java.time.LocalDate.now().getDayOfWeek().name();
            List<DashboardMeal> meals = new ArrayList<>();
            if ("MONDAY".equals(day)) {
                meals.add(new DashboardMeal("Breakfast", "7:30 AM", "Idli, Sambar, Coconut Chutney"));
                meals.add(new DashboardMeal("Lunch", "12:30 PM", "Rice, Dal, Veg Curry"));
                meals.add(new DashboardMeal("Snacks", "5:00 PM", "Tea, Biscuits, Banana"));
                meals.add(new DashboardMeal("Dinner", "8:00 PM", "Chapati, Paneer Curry, Jeera Rice"));
            } else if ("TUESDAY".equals(day)) {
                meals.add(new DashboardMeal("Breakfast", "7:30 AM", "Dosa, Peanut Chutney, Sambar"));
                meals.add(new DashboardMeal("Lunch", "12:30 PM", "Rice, Sambar, Potato Fry"));
                meals.add(new DashboardMeal("Snacks", "5:00 PM", "Tea, Bread Butter, Peanuts"));
                meals.add(new DashboardMeal("Dinner", "8:00 PM", "Chapati, Mixed Veg, Curd Rice"));
            } else if ("WEDNESDAY".equals(day)) {
                meals.add(new DashboardMeal("Breakfast", "7:30 AM", "Poori, Aloo Curry, Kesari"));
                meals.add(new DashboardMeal("Lunch", "12:30 PM", "Rice, Dal, Cabbage Curry"));
                meals.add(new DashboardMeal("Snacks", "5:00 PM", "Tea, Pakoda, Fruit"));
                meals.add(new DashboardMeal("Dinner", "8:00 PM", "Chapati, Egg Curry, Veg Pulao"));
            } else if ("THURSDAY".equals(day)) {
                meals.add(new DashboardMeal("Breakfast", "7:30 AM", "Upma, Coconut Chutney, Banana"));
                meals.add(new DashboardMeal("Lunch", "12:30 PM", "Rice, Sambar, Beans Fry"));
                meals.add(new DashboardMeal("Snacks", "5:00 PM", "Tea, Biscuits, Sprouts"));
                meals.add(new DashboardMeal("Dinner", "8:00 PM", "Chapati, Paneer Butter Masala, Lemon Rice"));
            } else if ("FRIDAY".equals(day)) {
                meals.add(new DashboardMeal("Breakfast", "7:30 AM", "Pongal, Chutney, Sambar"));
                meals.add(new DashboardMeal("Lunch", "12:30 PM", "Rice, Dal, Okra Fry"));
                meals.add(new DashboardMeal("Snacks", "5:00 PM", "Tea, Samosa, Banana"));
                meals.add(new DashboardMeal("Dinner", "8:00 PM", "Chapati, Chicken Curry, Plain Rice"));
            } else if ("SATURDAY".equals(day)) {
                meals.add(new DashboardMeal("Breakfast", "7:30 AM", "Vada, Chutney, Sambar"));
                meals.add(new DashboardMeal("Lunch", "12:30 PM", "Rice, Veg Kurma, Rasam"));
                meals.add(new DashboardMeal("Snacks", "5:00 PM", "Tea, Puffs, Groundnuts"));
                meals.add(new DashboardMeal("Dinner", "8:00 PM", "Chapati, Veg Curry, Tomato Rice"));
            } else {
                meals.add(new DashboardMeal("Breakfast", "7:30 AM", "Paratha, Kurma, Pickle"));
                meals.add(new DashboardMeal("Lunch", "12:30 PM", "Special Rice, Dal Tadka, Salad"));
                meals.add(new DashboardMeal("Snacks", "5:00 PM", "Tea, Samosa, Fruit"));
                meals.add(new DashboardMeal("Dinner", "8:00 PM", "Special Curry, Jeera Rice, Sweet"));
            }
            return meals;
        }

        List<DashboardNotice> getNotices() {
            List<DashboardNotice> notices = new ArrayList<>();
            notices.add(new DashboardNotice("Water supply will be interrupted on March 17 from 6 AM to 12 PM for maintenance.", "Mar 14", "Warden Office"));
            notices.add(new DashboardNotice("Semester-end checkout must be completed by May 15, 2026. Clear dues before exit.", "Mar 10", "Administration"));
            notices.add(new DashboardNotice("Cultural fest preparations: Hall B available Saturday and Sunday evenings for practice sessions.", "Mar 8", "Student Council"));
            return notices;
        }

        List<Vacancy> getVacancies(String bookingMonth) {
            List<Vacancy> list = new ArrayList<>();
            for (String type : RoomPlan.TYPES) {
                int capacity = RoomPlan.capacityFor(type);
                int rooms = RoomPlan.ROOMS_PER_FLOOR * RoomPlan.FLOORS;
                int total = rooms * capacity;
                int occupied = 0;
                for (Booking b : bookings) {
                    if (type.equalsIgnoreCase(b.roomType) && bookingMonth.equalsIgnoreCase(b.bookingMonth)) {
                        occupied++;
                    }
                }
                Vacancy v = new Vacancy();
                v.roomType = type;
                v.totalSlots = total;
                v.availableSlots = Math.max(0, total - occupied);
                list.add(v);
            }
            return list;
        }

        List<Room> getRoomStructure(String roomType, int members, String bookingMonth) {
            List<Room> list = new ArrayList<>();
            for (int floor = RoomPlan.FLOORS; floor >= 1; floor--) {
                for (int i = 1; i <= RoomPlan.ROOMS_PER_FLOOR; i++) {
                    String roomNo = String.format(Locale.ROOT, "%d%02d", floor, i);
                    Room r = buildRoom(roomType, members, bookingMonth, roomNo, String.valueOf(floor));
                    list.add(r);
                }
            }
            return list;
        }

        RoomDetails getRoomDetails(String roomType, int members, String bookingMonth, String roomNo) {
            Room room = getRoomByNo(roomType, members, bookingMonth, roomNo);
            if (room == null) return null;
            RoomDetails details = new RoomDetails();
            details.room = room;
            details.occupants = getOccupants(roomType, bookingMonth, room.roomNo);
            return details;
        }

        Room getRoomByNo(String roomType, int members, String bookingMonth, String roomNo) {
            if (roomNo.length() < 2) return null;
            String floor = roomNo.substring(0, 1);
            return buildRoom(roomType, members, bookingMonth, roomNo, floor);
        }

        Room buildRoom(String roomType, int members, String bookingMonth, String roomNo, String floor) {
            int occupied = 0;
            for (Booking b : bookings) {
                if (roomType.equalsIgnoreCase(b.roomType)
                        && bookingMonth.equalsIgnoreCase(b.bookingMonth)
                        && roomNo.equalsIgnoreCase(b.roomNo)) {
                    occupied++;
                }
            }
            Room r = new Room();
            r.roomNo = roomNo;
            r.floor = floor;
            r.capacity = Math.max(1, members);
            r.occupied = occupied;
            r.locked = occupied >= r.capacity;
            return r;
        }

        List<Occupant> getOccupants(String roomType, String bookingMonth, String roomNo) {
            List<Occupant> list = new ArrayList<>();
            for (Booking b : bookings) {
                if (roomType.equalsIgnoreCase(b.roomType)
                        && bookingMonth.equalsIgnoreCase(b.bookingMonth)
                        && roomNo.equalsIgnoreCase(b.roomNo)) {
                    Student s = studentsByReg.get(b.regNo);
                    Occupant o = new Occupant();
                    o.regNo = b.regNo;
                    o.name = s == null ? "Student" : s.name;
                    list.add(o);
                }
            }
            return list;
        }

        double getAttendancePercent(String regNo) {
            int seed = Math.abs(regNo.hashCode() % 40);
            return 60 + seed;
        }

        Warden wardenLogin(String wardenNo, String password) {
            Warden w = wardens.get(wardenNo);
            if (w == null) return null;
            if (!w.password.equals(password)) return null;
            return w;
        }

        void seedWardens() {
            wardens.clear();
            addWarden("WAR1001", "Aarav Rao", "1", "ALL", "ALL_TYPES", "war@1001");
            addWarden("WAR1002", "Nisha Iyer", "2", "ALL", "ALL_TYPES", "war@1002");
            addWarden("WAR1003", "Kiran Das", "3", "ALL", "ALL_TYPES", "war@1003");
            addWarden("WAR1004", "Rahul Menon", "4", "ALL", "ALL_TYPES", "war@1004");
            addWarden("WAR1005", "Sneha Gupta", "5", "ALL", "ALL_TYPES", "war@1005");
            addWarden("WAR1006", "Vikram Shah", "6", "ALL", "ALL_TYPES", "war@1006");
        }

        void addWarden(String wardenNo, String name, String floorNo, String group, String roomType, String password) {
            Warden w = new Warden();
            w.wardenNo = wardenNo;
            w.name = name;
            w.floorNo = floorNo;
            w.memberGroup = group;
            w.roomType = roomType;
            w.password = password;
            wardens.put(wardenNo, w);
        }

        int calculateFee(String roomType, int members) {
            RoomPlan plan = RoomPlan.forType(roomType);
            if (plan == null) return 0;
            int m = Math.max(plan.minMembers, Math.min(plan.maxMembers, members));
            double ratio = (double) (m - plan.minMembers) / (double) (plan.maxMembers - plan.minMembers);
            double fee = plan.maxFee - ((plan.maxFee - plan.minFee) * ratio);
            return (int) (Math.round(fee / 100.0) * 100);
        }

        private Student findStudentByReg(String regNo) {
            if (regNo == null || regNo.isEmpty()) return null;
            for (Student s : studentsByReg.values()) {
                if (regNo.equalsIgnoreCase(s.regNo)) {
                    return s;
                }
            }
            return null;
        }

        private Path resolveStoreFile() {
            Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            Path backendDir = (cwd.getFileName() != null && "backend".equalsIgnoreCase(cwd.getFileName().toString()))
                    ? cwd
                    : cwd.resolve("backend");
            return backendDir.resolve("data").resolve("store.bin");
        }

        private synchronized void loadState() {
            if (!Files.exists(storeFile)) {
                return;
            }
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(storeFile.toFile()))) {
                PersistedState state = (PersistedState) in.readObject();
                studentsByReg.clear();
                if (state.students != null) {
                    for (Student s : state.students) {
                        studentsByReg.put(s.regNo, s);
                    }
                }
                rebuildStudentEmailIndex();
                bookings.clear();
                if (state.bookings != null) {
                    bookings.addAll(state.bookings);
                }
                leaves.clear();
                if (state.leaves != null) {
                    leaves.addAll(state.leaves);
                }
                int maxLeaveId = state.leaveId;
                for (LeaveRequest leave : leaves) {
                    if (leave.id > maxLeaveId) {
                        maxLeaveId = leave.id;
                    }
                }
                leaveId.set(Math.max(maxLeaveId, 1000));
            } catch (Exception e) {
                System.err.println("Unable to load persisted data: " + e.getMessage());
            }
        }

        private synchronized void saveState() {
            try {
                Path parent = storeFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                PersistedState state = new PersistedState();
                state.students = new ArrayList<>(studentsByReg.values());
                state.bookings = new ArrayList<>(bookings);
                state.leaves = new ArrayList<>(leaves);
                state.leaveId = leaveId.get();
                try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(storeFile.toFile()))) {
                    out.writeObject(state);
                }
            } catch (Exception e) {
                System.err.println("Unable to save persisted data: " + e.getMessage());
            }
        }

        private void rebuildStudentEmailIndex() {
            studentsByEmail.clear();
            for (Student s : studentsByReg.values()) {
                studentsByEmail.put(trim(s.email).toLowerCase(Locale.ROOT), s);
            }
        }
    }

    private static class RoomPlan {
        static final String[] TYPES = {"AC", "NON_AC_STANDARD", "NON_AC_ECONOMY"};
        static final int FLOORS = 6;
        static final int ROOMS_PER_FLOOR = 8;

        final String type;
        final int minMembers;
        final int maxMembers;
        final int minFee;
        final int maxFee;

        RoomPlan(String type, int minMembers, int maxMembers, int minFee, int maxFee) {
            this.type = type;
            this.minMembers = minMembers;
            this.maxMembers = maxMembers;
            this.minFee = minFee;
            this.maxFee = maxFee;
        }

        static RoomPlan forType(String type) {
            if ("AC".equalsIgnoreCase(type)) return new RoomPlan("AC", 2, 6, 6000, 10000);
            if ("NON_AC_STANDARD".equalsIgnoreCase(type)) return new RoomPlan("NON_AC_STANDARD", 4, 8, 4500, 7500);
            if ("NON_AC_ECONOMY".equalsIgnoreCase(type)) return new RoomPlan("NON_AC_ECONOMY", 6, 10, 3000, 5500);
            return null;
        }

        static int capacityFor(String type) {
            RoomPlan plan = forType(type);
            return plan == null ? 4 : plan.maxMembers;
        }
    }

    private static class Student implements Serializable {
        private static final long serialVersionUID = 1L;
        final String regNo;
        final String name;
        final String email;
        final String dob;
        String password;

        Student(String regNo, String name, String email, String dob, String password) {
            this.regNo = regNo;
            this.name = name;
            this.email = email;
            this.dob = dob;
            this.password = password;
        }
    }

    private static class Booking implements Serializable {
        private static final long serialVersionUID = 1L;
        String regNo;
        String roomType;
        int members;
        String bookingMonth;
        String roomNo;
        String floor;
        int fee;
        String paymentStatus;
        long createdAt;
    }

    private static class DashboardMeal {
        final String label;
        final String time;
        final String item;

        DashboardMeal(String label, String time, String item) {
            this.label = label;
            this.time = time;
            this.item = item;
        }
    }

    private static class DashboardNotice {
        final String text;
        final String date;
        final String source;

        DashboardNotice(String text, String date, String source) {
            this.text = text;
            this.date = date;
            this.source = source;
        }
    }

    private static class Warden implements Serializable {
        private static final long serialVersionUID = 1L;
        String wardenNo;
        String name;
        String floorNo;
        String memberGroup;
        String roomType;
        String password;
    }

    private static class LeaveRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        int id;
        String regNo;
        String name;
        String contact;
        String purpose;
        String leaveDate;
        String returnDate;
        String floorNo = "";
        String status;
        String reviewedBy = "";
        String createdAt;
    }

    private static class Vacancy {
        String roomType;
        int totalSlots;
        int availableSlots;
    }

    private static class Room {
        String roomNo;
        String floor;
        int capacity;
        int occupied;
        boolean locked;
    }

    private static class Occupant {
        String regNo;
        String name;
    }

    private static class RoomDetails {
        Room room;
        List<Occupant> occupants;
    }

    private static class BookingResult {
        boolean success;
        String message;
        Booking booking;

        static BookingResult success(String message, Booking booking) {
            BookingResult r = new BookingResult();
            r.success = true;
            r.message = message;
            r.booking = booking;
            return r;
        }

        static BookingResult fail(String message) {
            BookingResult r = new BookingResult();
            r.success = false;
            r.message = message;
            return r;
        }
    }

    private static class PersistedState implements Serializable {
        private static final long serialVersionUID = 1L;
        List<Student> students = new ArrayList<>();
        List<Booking> bookings = new ArrayList<>();
        List<LeaveRequest> leaves = new ArrayList<>();
        int leaveId = 1000;
    }

    private static class EventBus {
        private final List<SseClient> clients = new CopyOnWriteArrayList<>();

        void add(SseClient client) {
            clients.add(client);
        }

        void remove(SseClient client) {
            clients.remove(client);
            client.close();
        }

        void publish(String eventName, String jsonData) {
            String payload = "event: " + eventName + "\n" + "data: " + jsonData + "\n\n";
            for (SseClient client : clients) {
                if (!client.send(payload)) {
                    remove(client);
                }
            }
        }
    }

    private static class SseClient {
        private final OutputStream output;
        private final Object lock = new Object();
        private volatile boolean open = true;

        SseClient(OutputStream output) {
            this.output = output;
        }

        boolean isOpen() {
            return open;
        }

        boolean send(String payload) {
            synchronized (lock) {
                if (!open) return false;
                try {
                    output.write(payload.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    return true;
                } catch (IOException e) {
                    close();
                    return false;
                }
            }
        }

        void close() {
            synchronized (lock) {
                if (!open) return;
                open = false;
                try {
                    output.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
