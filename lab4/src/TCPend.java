import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * TCP-like reliable data transfer over UDP.
 *
 * Sender:   java TCPend -p <port> -s <remote IP> -a <remote port> -f <file> -m <mtu> -c <sws>
 * Receiver: java TCPend -p <port> -m <mtu> -c <sws> -f <file>
 */
public class TCPend {

    // --- Protocol Constants ---
    static final int HEADER_SIZE = 24;       // 4+4+8+4+2+2 bytes
    static final int MAX_RETRANSMISSIONS = 16;
    static final int FLAG_SYN = 4;           // bit 2
    static final int FLAG_FIN = 2;           // bit 1
    static final int FLAG_ACK = 1;           // bit 0

    // --- Timeout State (EWMA) ---
    private double ertt;
    private double edev;
    private long timeout = 5_000_000_000L;   // initial 5 seconds (nanoseconds)
    private boolean firstAck = true;

    // --- Statistics ---
    private long dataTransferred;
    private int packetsSent;
    private int packetsReceived;
    private int outOfSequencePackets;
    private int checksumDiscarded;
    private int retransmissions;
    private int duplicateAcks;

    // --- Configuration ---
    private int localPort;
    private String remoteIP;
    private int remotePort;
    private String fileName;
    private int mtu;
    private int sws;
    private boolean isSender;

    private DatagramSocket socket;
    private long startTime;

    // ========================= MAIN =========================

    public static void main(String[] args) throws Exception {
        TCPend tcp = new TCPend();
        tcp.parseArgs(args);
        tcp.startTime = System.nanoTime();
        tcp.socket = new DatagramSocket(tcp.localPort);

        if (tcp.isSender) {
            tcp.runSender();
        } else {
            tcp.runReceiver();
        }

        tcp.socket.close();
    }

    private void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-p": localPort  = Integer.parseInt(args[++i]); break;
                case "-s": remoteIP   = args[++i]; isSender = true;  break;
                case "-a": remotePort = Integer.parseInt(args[++i]); break;
                case "-f": fileName   = args[++i];                   break;
                case "-m": mtu        = Integer.parseInt(args[++i]); break;
                case "-c": sws        = Integer.parseInt(args[++i]); break;
            }
        }
    }

    // ========================= PACKET BUILDING / PARSING =========================

    /**
     * Build a packet with the given header fields and optional data payload.
     * Computes and embeds the one's complement checksum.
     */
    private byte[] buildPacket(int seq, int ack, long timestamp,
                               int dataLen, int flags, byte[] data) {
        byte[] packet = new byte[HEADER_SIZE + dataLen];
        ByteBuffer buf = ByteBuffer.wrap(packet);

        buf.putInt(seq);
        buf.putInt(ack);
        buf.putLong(timestamp);
        buf.putInt((dataLen << 3) | flags);  // length (29 bits) | flags (3 bits)
        buf.putShort((short) 0);             // all zeros
        buf.putShort((short) 0);             // checksum placeholder

        if (data != null && dataLen > 0) {
            buf.put(data, 0, dataLen);
        }

        short checksum = computeChecksum(packet);
        packet[22] = (byte) ((checksum >> 8) & 0xFF);
        packet[23] = (byte) (checksum & 0xFF);
        return packet;
    }

    private int     getSeq(byte[] p)       { return ByteBuffer.wrap(p, 0, 4).getInt(); }
    private int     getAck(byte[] p)       { return ByteBuffer.wrap(p, 4, 4).getInt(); }
    private long    getTimestamp(byte[] p)  { return ByteBuffer.wrap(p, 8, 8).getLong(); }
    private int     getDataLen(byte[] p)   { return ByteBuffer.wrap(p, 16, 4).getInt() >> 3; }
    private int     getFlags(byte[] p)     { return ByteBuffer.wrap(p, 16, 4).getInt() & 0x7; }
    private boolean hasSYN(byte[] p)       { return (getFlags(p) & FLAG_SYN) != 0; }
    private boolean hasFIN(byte[] p)       { return (getFlags(p) & FLAG_FIN) != 0; }
    private boolean hasACK(byte[] p)       { return (getFlags(p) & FLAG_ACK) != 0; }

    // ========================= CHECKSUM =========================

    /** One's complement checksum over the entire byte array. */
    private short computeChecksum(byte[] data) {
        long sum = 0;
        int len = data.length;
        int i = 0;

        while (i < len - 1) {
            sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            if ((sum & 0x10000) != 0) {
                sum = (sum & 0xFFFF) + 1;
            }
            i += 2;
        }
        // Pad odd byte
        if (i < len) {
            sum += (data[i] & 0xFF) << 8;
            if ((sum & 0x10000) != 0) {
                sum = (sum & 0xFFFF) + 1;
            }
        }

        return (short) (~sum & 0xFFFF);
    }

    /** Verify checksum: if correct, summing all 16-bit words yields 0xFFFF. */
    private boolean verifyChecksum(byte[] pkt, int len) {
        long sum = 0;
        int i = 0;

        while (i < len - 1) {
            sum += ((pkt[i] & 0xFF) << 8) | (pkt[i + 1] & 0xFF);
            if ((sum & 0x10000) != 0) {
                sum = (sum & 0xFFFF) + 1;
            }
            i += 2;
        }
        if (i < len) {
            sum += (pkt[i] & 0xFF) << 8;
            if ((sum & 0x10000) != 0) {
                sum = (sum & 0xFFFF) + 1;
            }
        }

        return (sum & 0xFFFF) == 0xFFFF;
    }

    // ========================= I/O HELPERS =========================

    /** Send a packet and log it. */
    private void sendPacket(byte[] pkt, InetAddress addr, int port)
            throws IOException {
        socket.send(new DatagramPacket(pkt, pkt.length, addr, port));
        packetsSent++;
        printSegment("snd", pkt);
    }

    /** Receive a UDP datagram and return the trimmed payload. */
    private DatagramPacket receiveRaw() throws IOException {
        byte[] buf = new byte[HEADER_SIZE + mtu];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        socket.receive(dp);
        return dp;
    }

    /** Extract packet bytes from a DatagramPacket. */
    private byte[] extractPacket(DatagramPacket dp) {
        return Arrays.copyOf(dp.getData(), dp.getLength());
    }

    // ========================= OUTPUT =========================

    /** Print segment info: <snd/rcv> <time> <S> <A> <F> <D> <seq> <bytes> <ack> */
    private void printSegment(String dir, byte[] pkt) {
        double time = (System.nanoTime() - startTime) / 1e9;
        int flags = getFlags(pkt);
        System.out.printf("%s %.3f %s %s %s %s %d %d %d%n",
                dir, time,
                (flags & FLAG_SYN) != 0 ? "S" : "-",
                (flags & FLAG_ACK) != 0 ? "A" : "-",
                (flags & FLAG_FIN) != 0 ? "F" : "-",
                getDataLen(pkt) > 0     ? "D" : "-",
                getSeq(pkt), getDataLen(pkt), getAck(pkt));
    }

    /** Print final transfer statistics. */
    private void printStats() {
        System.out.printf("%d %d %d %d %d %d%n",
                dataTransferred, packetsSent, outOfSequencePackets,
                checksumDiscarded, retransmissions, duplicateAcks);
    }

    // ========================= TIMEOUT =========================

    /** Update retransmission timeout using EWMA (a=0.875, b=0.75). */
    private void updateTimeout(long ackTimestamp) {
        long now = System.nanoTime();

        if (firstAck) {
            ertt = now - ackTimestamp;
            edev = 0;
            timeout = (long) (2 * ertt);
            firstAck = false;
        } else {
            double srtt = now - ackTimestamp;
            double sdev = Math.abs(srtt - ertt);
            ertt = 0.875 * ertt + 0.125 * srtt;
            edev = 0.75  * edev + 0.25  * sdev;
            timeout = (long) (ertt + 4 * edev);
        }
    }

    /** Convert nanosecond timeout to milliseconds, minimum 1ms. */
    private int timeoutMs() {
        return Math.max(1, (int) (timeout / 1_000_000));
    }

    // ========================= SENDER =========================

    private void runSender() throws Exception {
        InetAddress destAddr = InetAddress.getByName(remoteIP);
        int maxData = mtu - HEADER_SIZE;

        byte[] fileData = readFile(fileName);
        int fileLen = fileData.length;

        // --- Three-way handshake ---
        int serverSeq = doSenderHandshake(destAddr);

        // --- Data transfer ---
        sendFileData(destAddr, serverSeq, fileData, fileLen, maxData);

        // --- Connection termination ---
        doSenderTermination(destAddr, serverSeq, fileLen);

        printStats();
    }

    /** Read entire file into a byte array. */
    private byte[] readFile(String path) throws IOException {
        File f = new File(path);
        byte[] data = new byte[(int) f.length()];
        FileInputStream fis = new FileInputStream(f);
        int totalRead = 0;
        while (totalRead < data.length) {
            int r = fis.read(data, totalRead, data.length - totalRead);
            if (r == -1) break;
            totalRead += r;
        }
        fis.close();
        return data;
    }

    /**
     * Perform three-way handshake as sender.
     * Returns the server's initial sequence number.
     */
    private int doSenderHandshake(InetAddress destAddr) throws Exception {
        byte[] synPkt = buildPacket(0, 0, System.nanoTime(), 0, FLAG_SYN, null);
        sendPacket(synPkt, destAddr, remotePort);

        socket.setSoTimeout(timeoutMs());
        int retries = 0;

        while (true) {
            try {
                DatagramPacket dp = receiveRaw();
                byte[] pkt = extractPacket(dp);
                packetsReceived++;

                if (!verifyChecksum(pkt, pkt.length)) {
                    checksumDiscarded++;
                    continue;
                }
                printSegment("rcv", pkt);

                if (hasSYN(pkt) && hasACK(pkt)) {
                    updateTimeout(getTimestamp(pkt));
                    int serverSeq = getSeq(pkt);
                    // Send ACK to complete handshake
                    byte[] ackPkt = buildPacket(1, serverSeq + 1,
                            System.nanoTime(), 0, FLAG_ACK, null);
                    sendPacket(ackPkt, destAddr, remotePort);
                    return serverSeq;
                }
            } catch (SocketTimeoutException e) {
                retries++;
                if (retries >= MAX_RETRANSMISSIONS) {
                    System.err.println("Connection failed");
                    printStats();
                    throw new IOException("Handshake failed");
                }
                retransmissions++;
                synPkt = buildPacket(0, 0, System.nanoTime(), 0, FLAG_SYN, null);
                sendPacket(synPkt, destAddr, remotePort);
            }
        }
    }

    /**
     * Transfer file data using sliding window.
     * Handles ACK processing, timeout retransmission, and fast retransmit.
     */
    private void sendFileData(InetAddress destAddr, int serverSeq,
                              byte[] fileData, int fileLen, int maxData)
            throws Exception {
        // tcpSeq -> [fileOffset, dataLen, retryCount]
        TreeMap<Integer, int[]> unacked = new TreeMap<>();
        Map<Integer, Long> sendTimes = new HashMap<>();

        int base = 0;
        int nextOff = 0;
        int lastAckedNum = 1;
        int dupCount = 0;

        while (base < fileLen) {
            // Fill the window with new segments
            int inFlight = unacked.size();
            while (nextOff < fileLen && inFlight < sws) {
                int dataLen = Math.min(maxData, fileLen - nextOff);
                byte[] data = Arrays.copyOfRange(fileData, nextOff, nextOff + dataLen);
                int tcpSeq = 1 + nextOff;
                long ts = System.nanoTime();

                byte[] pkt = buildPacket(tcpSeq, serverSeq + 1, ts,
                        dataLen, FLAG_ACK, data);
                sendPacket(pkt, destAddr, remotePort);

                unacked.put(tcpSeq, new int[]{nextOff, dataLen, 0});
                sendTimes.put(tcpSeq, ts);
                dataTransferred += dataLen;
                nextOff += dataLen;
                inFlight++;
            }

            // Wait for an ACK
            socket.setSoTimeout(timeoutMs());

            try {
                DatagramPacket dp = receiveRaw();
                byte[] pkt = extractPacket(dp);
                packetsReceived++;

                if (!verifyChecksum(pkt, pkt.length)) {
                    checksumDiscarded++;
                    continue;
                }
                printSegment("rcv", pkt);

                if (!hasACK(pkt)) continue;

                int ackNum = getAck(pkt);
                updateTimeout(getTimestamp(pkt));

                if (ackNum > lastAckedNum) {
                    // New ACK: slide window forward
                    Iterator<Map.Entry<Integer, int[]>> it =
                            unacked.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<Integer, int[]> entry = it.next();
                        if (entry.getKey() + entry.getValue()[1] <= ackNum) {
                            sendTimes.remove(entry.getKey());
                            it.remove();
                        } else {
                            break;
                        }
                    }
                    base = ackNum - 1;
                    lastAckedNum = ackNum;
                    dupCount = 0;

                } else if (ackNum == lastAckedNum && ackNum > 1) {
                    // Duplicate ACK
                    dupCount++;
                    duplicateAcks++;

                    if (dupCount >= 3) {
                        retransmitFirst(unacked, sendTimes, fileData,
                                destAddr, serverSeq);
                        dupCount = 0;
                    }
                }

            } catch (SocketTimeoutException e) {
                // Timeout: retransmit earliest unacked segment
                retransmitFirst(unacked, sendTimes, fileData,
                        destAddr, serverSeq);
            }
        }
    }

    /** Retransmit the first unacknowledged segment. */
    private void retransmitFirst(TreeMap<Integer, int[]> unacked,
                                 Map<Integer, Long> sendTimes,
                                 byte[] fileData,
                                 InetAddress destAddr, int serverSeq)
            throws Exception {
        if (unacked.isEmpty()) return;

        int firstSeq = unacked.firstKey();
        int[] info = unacked.get(firstSeq);

        if (info[2] >= MAX_RETRANSMISSIONS) {
            System.err.println("Max retransmissions exceeded");
            printStats();
            throw new IOException("Transfer failed");
        }

        info[2]++;
        byte[] data = Arrays.copyOfRange(fileData, info[0], info[0] + info[1]);
        long ts = System.nanoTime();
        byte[] pkt = buildPacket(firstSeq, serverSeq + 1, ts,
                info[1], FLAG_ACK, data);
        sendPacket(pkt, destAddr, remotePort);
        sendTimes.put(firstSeq, ts);
        retransmissions++;
    }

    /** Perform four-way connection termination as sender. */
    private void doSenderTermination(InetAddress destAddr, int serverSeq,
                                     int fileLen) throws Exception {
        int finSeq = 1 + fileLen;
        byte[] finPkt = buildPacket(finSeq, serverSeq + 1,
                System.nanoTime(), 0, FLAG_FIN | FLAG_ACK, null);
        sendPacket(finPkt, destAddr, remotePort);

        int retries = 0;
        socket.setSoTimeout(5000);

        while (true) {
            try {
                DatagramPacket dp = receiveRaw();
                byte[] pkt = extractPacket(dp);
                packetsReceived++;

                if (!verifyChecksum(pkt, pkt.length)) {
                    checksumDiscarded++;
                    continue;
                }
                printSegment("rcv", pkt);

                if (hasFIN(pkt) && hasACK(pkt)) {
                    byte[] lastAck = buildPacket(finSeq + 1, getSeq(pkt) + 1,
                            System.nanoTime(), 0, FLAG_ACK, null);
                    sendPacket(lastAck, destAddr, remotePort);
                    return;
                }
            } catch (SocketTimeoutException e) {
                retries++;
                if (retries >= MAX_RETRANSMISSIONS) return;
                retransmissions++;
                finPkt = buildPacket(finSeq, serverSeq + 1,
                        System.nanoTime(), 0, FLAG_FIN | FLAG_ACK, null);
                sendPacket(finPkt, destAddr, remotePort);
            }
        }
    }

    // ========================= RECEIVER =========================

    private void runReceiver() throws Exception {
        FileOutputStream fos = new FileOutputStream(fileName);
        TreeMap<Integer, byte[]> recvBuffer = new TreeMap<>();

        InetAddress senderAddr = null;
        int senderPort = 0;
        int expectedSeq = 1;
        int mySeq = 0;
        long latestTimestamp = 0;
        boolean connected = false;

        socket.setSoTimeout(0); // block until packet arrives

        while (true) {
            DatagramPacket dp = receiveRaw();
            byte[] pkt = extractPacket(dp);
            packetsReceived++;

            if (!verifyChecksum(pkt, pkt.length)) {
                checksumDiscarded++;
                continue;
            }
            printSegment("rcv", pkt);

            // --- Handshake: wait for SYN ---
            if (!connected) {
                if (hasSYN(pkt) && getSeq(pkt) == 0) {
                    senderAddr = dp.getAddress();
                    senderPort = dp.getPort();
                    latestTimestamp = getTimestamp(pkt);

                    byte[] synAck = buildPacket(mySeq, 1, latestTimestamp, 0,
                            FLAG_SYN | FLAG_ACK, null);
                    sendPacket(synAck, senderAddr, senderPort);
                    connected = true;
                }
                continue;
            }

            // Ignore packets from other sources
            if (!dp.getAddress().equals(senderAddr)
                    || dp.getPort() != senderPort) {
                continue;
            }

            int seq = getSeq(pkt);
            int dataLen = getDataLen(pkt);

            // --- FIN: connection termination ---
            if (hasFIN(pkt)) {
                byte[] finAck = buildPacket(mySeq + 1, seq + 1,
                        getTimestamp(pkt), 0, FLAG_FIN | FLAG_ACK, null);
                sendPacket(finAck, senderAddr, senderPort);

                // Wait for final ACK
                socket.setSoTimeout(5000);
                try {
                    while (true) {
                        DatagramPacket dp2 = receiveRaw();
                        byte[] p2 = extractPacket(dp2);
                        packetsReceived++;
                        if (!verifyChecksum(p2, p2.length)) {
                            checksumDiscarded++;
                            continue;
                        }
                        printSegment("rcv", p2);
                        if (hasACK(p2)) break;
                    }
                } catch (SocketTimeoutException e) { /* acceptable */ }
                break;
            }

            // --- ACK-only (handshake completion): skip ---
            if (dataLen == 0) continue;

            // --- Data segment ---
            byte[] data = Arrays.copyOfRange(pkt, HEADER_SIZE,
                    HEADER_SIZE + dataLen);

            if (seq == expectedSeq) {
                // In-order: write to file and flush any buffered segments
                fos.write(data);
                dataTransferred += dataLen;
                expectedSeq += dataLen;
                latestTimestamp = getTimestamp(pkt);

                while (recvBuffer.containsKey(expectedSeq)) {
                    byte[] buffered = recvBuffer.remove(expectedSeq);
                    int bLen = getDataLen(buffered);
                    fos.write(buffered, HEADER_SIZE, bLen);
                    dataTransferred += bLen;
                    long bts = getTimestamp(buffered);
                    if (bts > latestTimestamp) latestTimestamp = bts;
                    expectedSeq += bLen;
                }

            } else if (seq > expectedSeq) {
                // Out-of-order: buffer for later
                outOfSequencePackets++;
                recvBuffer.put(seq, pkt);

            } else {
                // Duplicate
                outOfSequencePackets++;
            }

            // Always send cumulative ACK
            byte[] ack = buildPacket(mySeq + 1, expectedSeq,
                    latestTimestamp, 0, FLAG_ACK, null);
            sendPacket(ack, senderAddr, senderPort);
        }

        fos.close();
        printStats();
    }
}
