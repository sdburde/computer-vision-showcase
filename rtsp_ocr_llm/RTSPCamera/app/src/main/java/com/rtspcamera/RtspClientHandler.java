package com.rtspcamera;

import android.util.Base64;
import android.util.Log;
import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class RtspClientHandler implements Runnable {

    private static final String TAG = "RtspClientHandler";
    private final Socket socket;
    private final CameraStreamer streamer;
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);

    private int sequenceNumber = 0;
    private long timestamp     = 0;
    private static final int SSRC            = 0x12345678;
    private static final int RTP_HEADER_SIZE = 12;
    private static final int MAX_RTP_PACKET  = 1400;

    // Transport negotiation
    private boolean useUdp    = false;
    private int clientRtpPort = 0;
    private String clientIp   = "";
    private static final int SERVER_RTP_PORT = 5004;

    public RtspClientHandler(Socket socket, CameraStreamer streamer) {
        this.socket = socket;
        this.streamer = streamer;
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
             OutputStream output = socket.getOutputStream()) {

            String line;
            StringBuilder request = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    String req = request.toString();
                    Log.d(TAG, "RTSP Request:\n" + req);

                    String response = handleRequest(req, output);
                    if (response != null) {
                        output.write(response.getBytes());
                        output.flush();
                    }

                    if (isPlaying.get()) {
                        streamRtp(output);
                        break;
                    }
                    request = new StringBuilder();
                } else {
                    request.append(line).append("\r\n");
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Client disconnected: " + e.getMessage());
        } finally {
            isPlaying.set(false);
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private String handleRequest(String request, OutputStream output) {
        String[] lines = request.split("\r\n");
        if (lines.length == 0) return null;

        String firstLine = lines[0];
        String cseq = getCSeq(lines);

        if (firstLine.startsWith("OPTIONS")) {
            return "RTSP/1.0 200 OK\r\n" +
                   "CSeq: " + cseq + "\r\n" +
                   "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n\r\n";

        } else if (firstLine.startsWith("DESCRIBE")) {
            String sdp = buildSdp();
            return "RTSP/1.0 200 OK\r\n" +
                   "CSeq: " + cseq + "\r\n" +
                   "Content-Type: application/sdp\r\n" +
                   "Content-Length: " + sdp.length() + "\r\n\r\n" + sdp;

        } else if (firstLine.startsWith("SETUP")) {
            String transportHeader = "";
            for (String l : lines) {
                if (l.startsWith("Transport:")) { transportHeader = l; break; }
            }

            clientIp = socket.getInetAddress().getHostAddress();

            if (transportHeader.contains("RTP/AVP/TCP") || transportHeader.contains("interleaved")) {
                useUdp = false;
                return "RTSP/1.0 200 OK\r\n" +
                       "CSeq: " + cseq + "\r\n" +
                       "Session: 12345678\r\n" +
                       "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n";
            } else {
                useUdp = true;
                clientRtpPort = extractClientPort(transportHeader);
                return "RTSP/1.0 200 OK\r\n" +
                       "CSeq: " + cseq + "\r\n" +
                       "Session: 12345678\r\n" +
                       "Transport: RTP/AVP;unicast;client_port=" + clientRtpPort + "-" +
                       (clientRtpPort + 1) + ";server_port=" + SERVER_RTP_PORT + "-" +
                       (SERVER_RTP_PORT + 1) + "\r\n\r\n";
            }

        } else if (firstLine.startsWith("PLAY")) {
            isPlaying.set(true);
            return "RTSP/1.0 200 OK\r\n" +
                   "CSeq: " + cseq + "\r\n" +
                   "Session: 12345678\r\n" +
                   "Range: npt=0.000-\r\n\r\n";

        } else if (firstLine.startsWith("TEARDOWN")) {
            isPlaying.set(false);
            return "RTSP/1.0 200 OK\r\n" +
                   "CSeq: " + cseq + "\r\n" +
                   "Session: 12345678\r\n\r\n";
        }

        return "RTSP/1.0 405 Method Not Allowed\r\nCSeq: " + cseq + "\r\n\r\n";
    }

    private int extractClientPort(String transport) {
        try {
            int idx = transport.indexOf("client_port=");
            if (idx >= 0) {
                String after = transport.substring(idx + 12);
                return Integer.parseInt(after.split("[^0-9]")[0]);
            }
        } catch (Exception ignored) {}
        return 5000;
    }

    private String buildSdp() {
        // Wait up to 1 s for the first codec-config frame so sprop-parameter-sets is populated.
        // This prevents decoders that rely on in-band SPS/PPS from failing on the first connect.
        if (streamer != null) {
            for (int i = 0; i < 20 && streamer.getSpsNal() == null; i++) {
                try { Thread.sleep(50); } catch (Exception ignored) {}
            }
        }

        byte[] sps = streamer != null ? streamer.getSpsNal() : null;
        byte[] pps = streamer != null ? streamer.getPpsNal() : null;

        String spsBase64 = "";
        String ppsBase64 = "";
        if (sps != null && sps.length > 4)
            spsBase64 = Base64.encodeToString(sps, 4, sps.length - 4, Base64.NO_WRAP);
        if (pps != null && pps.length > 4)
            ppsBase64 = Base64.encodeToString(pps, 4, pps.length - 4, Base64.NO_WRAP);

        // Derive profile-level-id from actual SPS bytes rather than hardcoding.
        // SPS layout after start code: [NAL hdr][profile_idc][constraint_flags][level_idc]
        String profileLevel = "42001f"; // fallback: Baseline L3.1
        if (sps != null) {
            int off = spsStartCodeLen(sps);
            if (sps.length >= off + 4) {
                profileLevel = String.format("%02x%02x%02x",
                    sps[off + 1] & 0xFF, sps[off + 2] & 0xFF, sps[off + 3] & 0xFF);
            }
        }

        String sprop = spsBase64.isEmpty() ? "" : ";sprop-parameter-sets=" + spsBase64 + "," + ppsBase64;
        int fps = streamer != null ? streamer.getVideoFps() : 30;

        return "v=0\r\n" +
               "o=- 0 0 IN IP4 127.0.0.1\r\n" +
               "s=RTSP Camera Stream\r\n" +
               "t=0 0\r\n" +
               "a=tool:RTSPCamera\r\n" +
               "a=type:broadcast\r\n" +
               "a=control:*\r\n" +
               "m=video 0 RTP/AVP 96\r\n" +
               "a=rtpmap:96 H264/90000\r\n" +
               "a=fmtp:96 packetization-mode=1;profile-level-id=" + profileLevel + sprop + "\r\n" +
               "a=framerate:" + fps + "\r\n" +
               "a=control:track0\r\n";
    }

    // ── streaming ─────────────────────────────────────────────────────────────

    private void streamRtp(OutputStream output) {
        if (useUdp) streamRtpUdp();
        else        streamRtpTcp(output);
    }

    private void streamRtpTcp(OutputStream output) {
        if (streamer == null) return;
        int fps = streamer.getVideoFps();

        LinkedBlockingQueue<byte[]> myQueue = streamer.createClientQueue();
        try {
            waitForSpsAndPps();
            byte[] sps = streamer.getSpsNal();
            byte[] pps = streamer.getPpsNal();
            if (sps != null) sendRtpPacketTcp(output, sps);
            if (pps != null) sendRtpPacketTcp(output, pps);

            while (isPlaying.get() && !socket.isClosed()) {
                byte[] nal = myQueue.poll(200, TimeUnit.MILLISECONDS);
                if (nal != null) {
                    sendRtpPacketTcp(output, nal);
                    timestamp += 90000L / fps;
                }
            }
        } catch (Exception e) {
            if (!socket.isClosed()) Log.e(TAG, "TCP stream error", e);
        } finally {
            streamer.removeClientQueue(myQueue);
        }
    }

    private void streamRtpUdp() {
        if (streamer == null) return;
        int fps = streamer.getVideoFps();

        LinkedBlockingQueue<byte[]> myQueue = streamer.createClientQueue();
        try (DatagramSocket udpSocket = new DatagramSocket()) {
            InetAddress clientAddr = InetAddress.getByName(clientIp);

            waitForSpsAndPps();
            byte[] sps = streamer.getSpsNal();
            byte[] pps = streamer.getPpsNal();
            if (sps != null) sendRtpPacketUdp(udpSocket, clientAddr, sps);
            if (pps != null) sendRtpPacketUdp(udpSocket, clientAddr, pps);

            while (isPlaying.get() && !socket.isClosed()) {
                byte[] nal = myQueue.poll(200, TimeUnit.MILLISECONDS);
                if (nal != null) {
                    sendRtpPacketUdp(udpSocket, clientAddr, nal);
                    timestamp += 90000L / fps;
                }
            }
        } catch (Exception e) {
            if (!socket.isClosed()) Log.e(TAG, "UDP stream error", e);
        } finally {
            streamer.removeClientQueue(myQueue);
        }
    }

    private void waitForSpsAndPps() {
        for (int i = 0; i < 100 && (streamer.getSpsNal() == null || streamer.getPpsNal() == null); i++) {
            try { Thread.sleep(50); } catch (Exception ignored) {}
        }
    }

    // ── packet builders ───────────────────────────────────────────────────────

    private void sendRtpPacketTcp(OutputStream output, byte[] nal) {
        try {
            int offset = startCodeOffset(nal);
            int length = nal.length - offset;
            if (length <= MAX_RTP_PACKET) {
                writeRtpOverTcp(output, buildRtpPacket(nal, offset, length, true), 0);
            } else {
                sendFragmentedNalTcp(output, nal, offset, length);
            }
        } catch (Exception e) {
            Log.e(TAG, "TCP packet error", e);
        }
    }

    private void sendRtpPacketUdp(DatagramSocket sock, InetAddress addr, byte[] nal) {
        try {
            int offset = startCodeOffset(nal);
            int length = nal.length - offset;
            if (length <= MAX_RTP_PACKET) {
                byte[] pkt = buildRtpPacket(nal, offset, length, true);
                sock.send(new DatagramPacket(pkt, pkt.length, addr, clientRtpPort));
            } else {
                sendFragmentedNalUdp(sock, addr, nal, offset, length);
            }
        } catch (Exception e) {
            Log.e(TAG, "UDP packet error", e);
        }
    }

    private int startCodeOffset(byte[] nal) {
        if (nal.length > 3 && nal[0] == 0 && nal[1] == 0)
            return (nal[2] == 0 && nal.length > 4) ? 4 : 3;
        return 0;
    }

    /** Returns the byte offset of the NAL content past the start code in an SPS buffer. */
    private static int spsStartCodeLen(byte[] sps) {
        if (sps.length > 3 && sps[0] == 0 && sps[1] == 0 && sps[2] == 0 && sps[3] == 1) return 4;
        if (sps.length > 2 && sps[0] == 0 && sps[1] == 0 && sps[2] == 1) return 3;
        return 0;
    }

    private void sendFragmentedNalTcp(OutputStream out, byte[] nal, int offset, int nalLen) throws IOException {
        byte nalHdr = nal[offset];
        byte fuType = (byte) (nalHdr & 0x1F);
        int pos = offset + 1;
        boolean first = true;
        while (pos < offset + nalLen) {
            int chunk = Math.min((offset + nalLen) - pos, MAX_RTP_PACKET - 2);
            boolean last = (pos + chunk >= offset + nalLen);
            byte[] fu = buildFuPacket(nal, nalHdr, fuType, pos, chunk, first, last);
            writeRtpOverTcp(out, buildRtpPacket(fu, 0, fu.length, last), 0);
            pos += chunk; first = false;
        }
    }

    private void sendFragmentedNalUdp(DatagramSocket sock, InetAddress addr,
                                       byte[] nal, int offset, int nalLen) throws IOException {
        byte nalHdr = nal[offset];
        byte fuType = (byte) (nalHdr & 0x1F);
        int pos = offset + 1;
        boolean first = true;
        while (pos < offset + nalLen) {
            int chunk = Math.min((offset + nalLen) - pos, MAX_RTP_PACKET - 2);
            boolean last = (pos + chunk >= offset + nalLen);
            byte[] fu = buildFuPacket(nal, nalHdr, fuType, pos, chunk, first, last);
            byte[] pkt = buildRtpPacket(fu, 0, fu.length, last);
            sock.send(new DatagramPacket(pkt, pkt.length, addr, clientRtpPort));
            pos += chunk; first = false;
        }
    }

    private byte[] buildFuPacket(byte[] nal, byte nalHdr, byte fuType,
                                  int pos, int size, boolean first, boolean last) {
        byte[] fu = new byte[size + 2];
        fu[0] = (byte) ((nalHdr & 0xE0) | 28);
        fu[1] = (byte) (fuType | (first ? 0x80 : 0) | (last ? 0x40 : 0));
        System.arraycopy(nal, pos, fu, 2, size);
        return fu;
    }

    private byte[] buildRtpPacket(byte[] payload, int offset, int length, boolean marker) {
        byte[] pkt = new byte[RTP_HEADER_SIZE + length];
        pkt[0] = (byte) 0x80;
        pkt[1] = (byte) (96 | (marker ? 0x80 : 0));
        pkt[2] = (byte) (sequenceNumber >> 8);
        pkt[3] = (byte) (sequenceNumber & 0xFF);
        sequenceNumber++;
        pkt[4] = (byte) (timestamp >> 24); pkt[5] = (byte) (timestamp >> 16);
        pkt[6] = (byte) (timestamp >> 8);  pkt[7] = (byte) (timestamp & 0xFF);
        pkt[8]  = (byte) (SSRC >> 24); pkt[9]  = (byte) (SSRC >> 16);
        pkt[10] = (byte) (SSRC >> 8);  pkt[11] = (byte) (SSRC & 0xFF);
        System.arraycopy(payload, offset, pkt, RTP_HEADER_SIZE, length);
        return pkt;
    }

    private void writeRtpOverTcp(OutputStream out, byte[] packet, int channel) throws IOException {
        byte[] hdr = { '$', (byte) channel, (byte)(packet.length >> 8), (byte)(packet.length & 0xFF) };
        synchronized (out) {
            out.write(hdr);
            out.write(packet);
            out.flush();
        }
    }

    private String getCSeq(String[] lines) {
        for (String l : lines)
            if (l.startsWith("CSeq:")) return l.substring(5).trim();
        return "0";
    }
}
