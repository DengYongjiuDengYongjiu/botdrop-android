package app.botdrop.shizuku;

import com.termux.shared.logger.Logger;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ShizukuBridgeServer {

    public interface StatusProvider {
        String getStatus();
        boolean isShellServiceBound();
    }

    private static final String LOG_TAG = "ShizukuBridgeServer";
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 30000;
    private static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 18790;

    private final int mPort;
    private final String mAuthToken;
    private final ShizukuShellExecutor mExecutor;
    private final StatusProvider mStatusProvider;

    private ServerSocket mServerSocket;
    private ExecutorService mWorkers;
    private Thread mAcceptThread;
    private volatile boolean mRunning;

    public ShizukuBridgeServer(String host, int port, String authToken,
                              ShizukuShellExecutor executor,
                              StatusProvider statusProvider) {
        mPort = port <= 0 ? DEFAULT_PORT : port;
        mAuthToken = authToken == null ? "" : authToken;
        mExecutor = executor;
        mStatusProvider = statusProvider;
    }

    public boolean isRunning() {
        return mRunning;
    }

    public synchronized boolean start() {
        if (mRunning) {
            return true;
        }

        try {
            mServerSocket = new ServerSocket();
            mServerSocket.bind(new InetSocketAddress(DEFAULT_HOST, mPort));
            mServerSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
            mWorkers = Executors.newFixedThreadPool(4);
            mRunning = true;
            mAcceptThread = new Thread(this::acceptLoop, "ShizukuBridgeServer");
            mAcceptThread.start();
            Logger.logInfo(LOG_TAG, "Bridge server started on " + DEFAULT_HOST + ":" + mPort);
            return true;
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Unable to start bridge server: " + e.getMessage());
            mRunning = false;
            closeQuietly(mServerSocket);
            mServerSocket = null;
            return false;
        }
    }

    public synchronized void stop() {
        mRunning = false;
        closeQuietly(mServerSocket);
        mServerSocket = null;
        if (mAcceptThread != null) {
            mAcceptThread.interrupt();
            mAcceptThread = null;
        }
        if (mWorkers != null) {
            mWorkers.shutdownNow();
            mWorkers = null;
        }
        Logger.logInfo(LOG_TAG, "Bridge server stopped");
    }

    private void acceptLoop() {
        while (mRunning && !Thread.currentThread().isInterrupted()) {
            try {
                Socket socket = mServerSocket.accept();
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                if (mWorkers != null) {
                    mWorkers.execute(() -> handleClient(socket));
                }
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (!mRunning) {
                    return;
                }
                Logger.logWarn(LOG_TAG, "Accept failed: " + e.getMessage());
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
            );

            String requestLine = reader.readLine();
            if (requestLine == null) {
                writeResponse(socket, 400, "", buildError("Bad request"));
                return;
            }

            String[] requestParts = requestLine.split(" ");
            if (requestParts.length < 2) {
                writeResponse(socket, 400, "", buildError("Invalid request line"));
                return;
            }

            String method = requestParts[0].toUpperCase(Locale.ROOT);
            String path = requestParts[1];

            Map<String, String> headers = new HashMap<>();
            int contentLength = 0;

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    break;
                }
                int sep = line.indexOf(':');
                if (sep > 0 && sep < line.length() - 1) {
                    String key = line.substring(0, sep).trim().toLowerCase(Locale.ROOT);
                    String value = line.substring(sep + 1).trim();
                    headers.put(key, value);

                    if ("content-length".equals(key)) {
                        try {
                            contentLength = Integer.parseInt(value);
                        } catch (NumberFormatException ignored) {
                            contentLength = 0;
                        }
                    }
                }
            }

            String response;
            if ("GET".equals(method) && "/shizuku/status".equals(path)) {
                if (!authorize(headers)) {
                    writeResponse(socket, 401, "", buildError("Unauthorized"));
                    return;
                }
                response = buildStatusPayload();
                writeResponse(socket, 200, "application/json", response);
                return;
            }

            if (!"POST".equals(method) || !"/shizuku/exec".equals(path)) {
                writeResponse(socket, 404, "", buildError("Not found"));
                return;
            }

            if (!authorize(headers)) {
                writeResponse(socket, 401, "", buildError("Unauthorized"));
                return;
            }

            if (contentLength > MAX_REQUEST_BYTES) {
                writeResponse(socket, 413, "", buildError("Payload too large"));
                return;
            }

            String body = readBody(reader, contentLength);
            response = handleExec(body);
            writeResponse(socket, 200, "application/json", response);
        } catch (Throwable e) {
            Logger.logWarn(LOG_TAG, "Request handling error: " + e.getMessage());
            writeResponse(socket, 500, "", buildError("Internal server error"));
        } finally {
            closeQuietly(socket);
        }
    }

    private String handleExec(String body) {
        if (body == null || body.isEmpty()) {
            return buildError("Missing request body");
        }

        try {
            JSONObject req = new JSONObject(body);
            String command = req.optString("command", "").trim();
            if (command.isEmpty()) {
                return buildError("command missing");
            }

            int timeout = req.optInt("timeoutMs", 30000);

            if (mExecutor == null) {
                return buildUnavailableResponse("executor missing");
            }
            if (mExecutor.isBound()) {
                ShizukuShellExecutor.Result result = mExecutor.executeSync(command, timeout);
                if (result != null && result.success) {
                    return toJsonResponse(result, "shizuku", false).toString();
                }
                if (result != null && isShizukuUnavailable(result)) {
                    Logger.logWarn(LOG_TAG, "Shizuku unavailable: " + result.stderr);
                    return buildUnavailableResponse(result.stderr == null ? "shizuku unavailable" : result.stderr);
                }
                if (result == null) {
                    Logger.logWarn(LOG_TAG, "Shizuku executor returned null");
                    return buildUnavailableResponse("executor unavailable");
                }

                // keep Shizuku result on normal command failures (e.g. command syntax)
                return toJsonResponse(result, "shizuku", false).toString();
            }

            Logger.logWarn(LOG_TAG, "Shizuku shell service not bound");
            return buildUnavailableResponse("shizuku not bound");
        } catch (JSONException e) {
            return buildError("Invalid JSON body");
        } catch (Exception e) {
            return buildError("Execution failed: " + e.getMessage());
        }
    }

    private boolean isShizukuUnavailable(ShizukuShellExecutor.Result result) {
        if (result == null || result.stderr == null) {
            return true;
        }
        String stderr = result.stderr.toLowerCase(Locale.ROOT);
        return stderr.contains("shizuku execution unavailable")
            || stderr.contains("binder not ready")
            || stderr.contains("permission not granted")
            || stderr.contains("no permission")
            || stderr.contains("security denied")
            || stderr.contains("process failed")
            || stderr.contains("shizuku service unavailable")
            || "false".equalsIgnoreCase(String.valueOf(result.success)) && result.exitCode != 0 && result.stderr.trim().isEmpty();
    }

    private String buildUnavailableResponse(String reason) {
        try {
            JSONObject response = new JSONObject();
            response.put("ok", false);
            response.put("exitCode", -1);
            response.put("stdout", "");
            response.put("stderr", "Shizuku unavailable: " + (reason == null ? "" : reason));
            response.put("mode", "shizuku");
            response.put("fallback", false);
            response.put("fallbackReason", "");
            return response.toString();
        } catch (JSONException e) {
            return buildError("Shizuku unavailable: " + (reason == null ? "" : reason));
        }
    }

    private JSONObject toJsonResponse(ShizukuShellExecutor.Result result, String mode, boolean fallback) {
        JSONObject response = new JSONObject();
        try {
            response.put("ok", result.success);
            response.put("exitCode", result.exitCode);
            response.put("stdout", result.stdout == null ? "" : result.stdout);
            response.put("stderr", result.stderr == null ? "" : result.stderr);
            response.put("mode", mode);
            response.put("fallback", fallback);
        } catch (JSONException ignored) {
        }
        return response;
    }

    private String buildStatusPayload() {
        try {
            JSONObject response = new JSONObject();
            response.put("status", mStatusProvider == null ? "UNKNOWN" : mStatusProvider.getStatus());
            response.put("serviceBound", mStatusProvider != null && mStatusProvider.isShellServiceBound());
            return response.toString();
        } catch (JSONException e) {
            return buildError("Failed to build status");
        }
    }

    private String buildError(String message) {
        try {
            JSONObject response = new JSONObject();
            response.put("ok", false);
            response.put("error", message);
            response.put("exitCode", -1);
            response.put("stdout", "");
            response.put("stderr", message);
            return response.toString();
        } catch (JSONException e) {
            return "{\"ok\":false,\"error\":\"" + escape(message) + "\"}";
        }
    }

    private boolean authorize(Map<String, String> headers) {
        if (mAuthToken == null || mAuthToken.isEmpty()) {
            return false;
        }

        String auth = headers.get("authorization");
        return auth != null && auth.equals("Bearer " + mAuthToken);
    }

    private String readBody(BufferedReader reader, int contentLength) throws IOException {
        if (contentLength <= 0) {
            return "";
        }

        char[] body = new char[contentLength];
        int readTotal = 0;
        while (readTotal < contentLength) {
            int read = reader.read(body, readTotal, contentLength - readTotal);
            if (read <= 0) {
                break;
            }
            readTotal += read;
        }
        if (readTotal <= 0) {
            return "";
        }
        return new String(body, 0, readTotal);
    }

    private void writeResponse(Socket socket, int code, String contentType, String body) {
        try {
            if (contentType == null || contentType.isEmpty()) {
                contentType = "application/json";
            }
            if (body == null) {
                body = "";
            }

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            if (payload.length > MAX_REQUEST_BYTES) {
                payload = "{\"ok\":false,\"error\":\"Response too large\"}".getBytes(StandardCharsets.UTF_8);
            }

            OutputStream out = socket.getOutputStream();
            String statusLine = "HTTP/1.1 " + code + "\r\n";
            String headers = "Content-Type: " + contentType + "; charset=utf-8\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
            out.write(statusLine.getBytes(StandardCharsets.UTF_8));
            out.write(headers.getBytes(StandardCharsets.UTF_8));
            out.write(payload);
            out.flush();
        } catch (IOException e) {
            Logger.logWarn(LOG_TAG, "Failed to write response: " + e.getMessage());
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private String escape(String input) {
        if (input == null) {
            return "";
        }
        try {
            return input.replace("\\", "\\\\").replace("\"", "\\\"");
        } catch (Exception ignored) {
            return input;
        }
    }
}
