import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class Iperfer {
    private static final int MIN_PORT = 1024;
    private static final int MAX_PORT = 65535;
    private static final int CHUNK_SIZE = 1000;

    public static void main(String[] args) {
        if (args == null || args.length == 0) {
            printArgErrorAndExit();
        }

        boolean isClient = "-c".equals(args[0]);
        boolean isServer = "-s".equals(args[0]);

        if (isClient) {
            handleClient(args);
        } else if (isServer) {
            handleServer(args);
        } else {
            printArgErrorAndExit();
        }
    }

    private static void handleClient(String[] args) {
        if (args.length != 7) {
            printArgErrorAndExit();
        }

        if (!"-h".equals(args[1]) || !"-p".equals(args[3]) || !"-t".equals(args[5])) {
            printArgErrorAndExit();
        }

        String host = args[2];
        int port = parsePortOrExit(args[4]);
        int timeSeconds = parsePositiveIntOrExit(args[6]);

        byte[] buffer = new byte[CHUNK_SIZE];
        long totalBytes = 0;

        // long startTime = System.nanoTime();
        // long endTime = startTime + (long) timeSeconds * 1_000_000_000L;

        long startTime = 0;
        long endTime = 0;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port));
            OutputStream out = socket.getOutputStream();

            startTime = System.nanoTime();
            endTime = startTime + (long) timeSeconds * 1_000_000_000L;
            while (System.nanoTime() < endTime) {
                out.write(buffer);
                totalBytes += buffer.length;
            }
            out.flush();
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }

        double elapsedSeconds = (System.nanoTime() - startTime) / 1_000_000_000.0;
        printClientSummary(totalBytes, elapsedSeconds);
    }

    private static void handleServer(String[] args) {
        if (args.length != 3) {
            printArgErrorAndExit();
        }

        if (!"-p".equals(args[1])) {
            printArgErrorAndExit();
        }

        int port = parsePortOrExit(args[2]);

        byte[] buffer = new byte[CHUNK_SIZE];
        long totalBytes = 0;
        long startTime = 0;
        long endTime = 0;

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            try (Socket clientSocket = serverSocket.accept()) {
                InputStream in = clientSocket.getInputStream();
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (startTime == 0) {
                        startTime = System.nanoTime();
                    }
                    endTime = System.nanoTime();
                    totalBytes += read;
                }
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }

        if (startTime == 0) {
            startTime = System.nanoTime();
            endTime = startTime;
        }

        double elapsedSeconds = (endTime - startTime) / 1_000_000_000.0;
        if (elapsedSeconds <= 0) {
            elapsedSeconds = 0.000000001;
        }
        printServerSummary(totalBytes, elapsedSeconds);
    }

    private static int parsePortOrExit(String value) {
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            printPortErrorAndExit();
            return -1;
        }

        if (port < MIN_PORT || port > MAX_PORT) {
            printPortErrorAndExit();
        }
        return port;
    }

    private static int parsePositiveIntOrExit(String value) {
        int number;
        try {
            number = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            printArgErrorAndExit();
            return -1;
        }

        if (number <= 0) {
            printArgErrorAndExit();
        }
        return number;
    }

    private static void printClientSummary(long totalBytes, double elapsedSeconds) {
        double kb = totalBytes / 1000.0;
        double mbps = (totalBytes * 8.0) / (elapsedSeconds * 1_000_000.0);
        System.out.printf("sent=%.0f KB rate=%.3f Mbps%n", kb, mbps);
    }

    private static void printServerSummary(long totalBytes, double elapsedSeconds) {
        double kb = totalBytes / 1000.0;
        double mbps = (totalBytes * 8.0) / (elapsedSeconds * 1_000_000.0);
        System.out.printf("received=%.0f KB rate=%.3f Mbps%n", kb, mbps);
    }

    private static void printArgErrorAndExit() {
        System.out.println("Error: missing or additional arguments");
        System.exit(1);
    }

    private static void printPortErrorAndExit() {
        System.out.println("Error: port number must be in the range 1024 to 65535");
        System.exit(1);
    }
}
