import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

/**
 * This class contains the implementation of UDP Client.
 */
public class Client {

    static int acknowledgementCount = 0;

    static long sequenceNumber = 0;
    static List<Long> receivedPackets = new ArrayList<>();
    static int timeout = 3000;


    public static void main(String[] args) throws Exception {

        // Router address
        String routerHost = "localhost";
        int routerPort = 3333;

        ArrayList<String> requestList;
        File file = new File("attachment");
        file.mkdir();
        while (true) {
            String url = "";
            String request = "";
            System.out.print("Enter command : ");
            receivedPackets.clear();
            sequenceNumber = 0;
            acknowledgementCount = 0;
            Scanner sc = new Scanner(System.in);
            request = sc.nextLine();

            if (request.isEmpty() || request.length() == 0) {
                System.out.println("Invalid Command");
                continue;
            }
            String[] clientRequestArray = request.split(" ");
            requestList = new ArrayList<>();
            for (String s : clientRequestArray) {
                if (s.startsWith("http://")) {
                    url = s;
                }
                requestList.add(s);
            }

            String serverHost = new URL(url).getHost();
            int serverPort = new URL(url).getPort();
//            System.out.println(serverHost);
//            System.out.println(serverPort);

            SocketAddress routerAddress = new InetSocketAddress(routerHost, routerPort);
            InetSocketAddress serverAddress = new InetSocketAddress(serverHost, serverPort);

            startConnection(routerAddress, serverAddress);
            runClient(routerAddress, serverAddress, request);

        }
    }

    /**
     * This method will establish connection and also parse the user request URL and set different value in
     * Request class based on different conditions
     *
     * @param routerAddress Socket Address of the router
     * @param serverAddress Socket Address of the server
     */
    private static void startConnection(SocketAddress routerAddress, InetSocketAddress serverAddress) throws Exception {

        try (DatagramChannel channel = DatagramChannel.open()) {
            String msg = "Hi S";
            sequenceNumber++;
            // SYN
            Packet p = new Packet.Builder().setType(0).setSequenceNumber(sequenceNumber)
                    .setPortNumber(serverAddress.getPort()).setPeerAddress(serverAddress.getAddress())
                    .setPayload(msg.getBytes()).create();
            //setting the server address in the setPeerAddress
            channel.send(p.toBuffer(), routerAddress); //Sending to the router
            System.out.println("Sending Hi from Client");

            channel.configureBlocking(false);
            Selector selector = Selector.open();
            channel.register(selector, OP_READ);

            selector.select(timeout);

            Set<SelectionKey> keys = selector.selectedKeys();
            if (keys.isEmpty()) {
                System.out.println("No response after timeout\nSending again");
                resend(channel, p, routerAddress);
            }

            ByteBuffer buf = ByteBuffer.allocate(Packet.MAX_LEN).order(ByteOrder.BIG_ENDIAN);
            //buf.flip();
            Packet response = Packet.fromBuffer(buf);
            //buf.flip();
            String payload = new String(response.getPayload(), StandardCharsets.UTF_8);
            System.out.println(payload + "received..!");
            receivedPackets.add(response.getSequenceNumber());
            keys.clear();

        }
    }

    /**
     * This method will resend request to router if timeout occurs
     */
    private static void resend(DatagramChannel channel, Packet p, SocketAddress routerAddress) throws IOException {

        channel.send(p.toBuffer(), routerAddress);
        System.out.println(new String(p.getPayload()));

        if (new String(p.getPayload()).equals("Received")) {
            acknowledgementCount++;
        }

        channel.configureBlocking(false);
        Selector selector = Selector.open();
        channel.register(selector, OP_READ);
        selector.select(timeout);

        Set<SelectionKey> keys = selector.selectedKeys();
        if (keys.isEmpty() && acknowledgementCount < 10) {

            System.out.println("No response after timeout\nSending again");
            resend(channel, p, routerAddress);

        } else {
            return;
        }
    }

    /**
     * This method will send UDP request to router based on client input
     */
    private static void runClient(SocketAddress routerAddr, InetSocketAddress serverAddr, String msg)
            throws IOException {
        String dir = System.getProperty("user.dir");
        try (DatagramChannel channel = DatagramChannel.open()) {
            sequenceNumber++;
            Packet p = new Packet.Builder().setType(0).setSequenceNumber(sequenceNumber)
                    .setPortNumber(serverAddr.getPort()).setPeerAddress(serverAddr.getAddress())
                    .setPayload(msg.getBytes()).create();
            channel.send(p.toBuffer(), routerAddr);
            System.out.println("Request sent to the Router.");

            // Try to receive a packet within timeout.
            channel.configureBlocking(false);
            Selector selector = Selector.open();
            channel.register(selector, OP_READ);
            selector.select(timeout);

            Set<SelectionKey> keys = selector.selectedKeys();
            if (keys.isEmpty()) {
                System.out.println("Timeout and no response!\nRetrying...");
                resend(channel, p, routerAddr);
            }

            // We just want a single response.
            ByteBuffer buf = ByteBuffer.allocate(Packet.MAX_LEN).order(ByteOrder.BIG_ENDIAN);
            SocketAddress router = channel.receive(buf);
            buf.flip();
            Packet response = Packet.fromBuffer(buf);
            //buf.flip();
            String payload = new String(response.getPayload(), UTF_8);

            if (!receivedPackets.contains(response.getSequenceNumber())) {

                receivedPackets.add(response.getSequenceNumber());
                System.out.println("\nResponse from Server : \n" + payload);

                // Sending ACK for the received of the response
                sequenceNumber++;
                Packet pAck = new Packet.Builder().setType(0).setSequenceNumber(sequenceNumber)
                        .setPortNumber(serverAddr.getPort()).setPeerAddress(serverAddr.getAddress())
                        .setPayload("Received".getBytes()).create();
                channel.send(pAck.toBuffer(), routerAddr);

                // Try to receive a packet within timeout.
                channel.configureBlocking(false);
                selector = Selector.open();
                channel.register(selector, OP_READ);
                selector.select(timeout);

                keys = selector.selectedKeys();
                if (keys.isEmpty()) {
                    resend(channel, pAck, router);
                }

                buf.flip();

                System.out.println("Connection terminated");
                keys.clear();

                sequenceNumber++;
                Packet pClose = new Packet.Builder().setType(0).setSequenceNumber(sequenceNumber)
                        .setPortNumber(serverAddr.getPort()).setPeerAddress(serverAddr.getAddress())
                        .setPayload("Ok".getBytes()).create();
                channel.send(pClose.toBuffer(), routerAddr);
                System.out.println("OK sent");
            }
        }
    }
}
