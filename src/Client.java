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

    public static int acknowledgementCount = 0;
    public static long sequenceNumber = 0;
    public static List<Long> receivedPackets = new ArrayList<>();

    //Setting the Timeout for the Request to be retransmitted
    public static final int timeout = 3000;

    // Router Host and Port Number
    public static final String routerHost = "localhost";
    public static final int routerPort = 3333;

    public static void main(String[] args) throws Exception {


        ArrayList<String> requestList;
        File file = new File("attachment");
        file.mkdir();
        while (true) {

            //initializing some variables for the request to be sent
            String serverURL = "";
            String clientRequest = "";
            receivedPackets.clear();
            sequenceNumber = 0;
            acknowledgementCount = 0;

            //Get input from the user and validate
            clientRequest = getInputFromUser();
            if (clientRequest == null) continue;


            //Fetching the Server URL and Port from The client Request
            serverURL = retrieveServerURL(serverURL, clientRequest);
            String serverHost = new URL(serverURL).getHost();
            int serverPort = new URL(serverURL).getPort();

            //Initialising Socket for Router and Server
            SocketAddress routerSocketAddress = new InetSocketAddress(routerHost, routerPort);
            InetSocketAddress serverSocketAddress = new InetSocketAddress(serverHost, serverPort);

            //Establish connection with Router
            establishRouterConnection(routerSocketAddress, serverSocketAddress);

            //Send the User request across to the Server VIA the Router
            executeClientRequest(routerSocketAddress, serverSocketAddress, clientRequest);

        }
    }

    private static String retrieveServerURL(String serverURL, String clientRequest) {
        String[] clientRequestSplitArray = clientRequest.split(" ");
        for (String request : clientRequestSplitArray) {
            if (request.startsWith("http://")) {
                serverURL = request;
            }
        }
        return serverURL;
    }

    private static String getInputFromUser() {
        String request;
        System.out.print("Enter command : ");
        Scanner sc = new Scanner(System.in);
        request = sc.nextLine();
        if (request.isEmpty()) {
            System.out.println("Invalid Command");
            return null;
        }
        return request;
    }

    /**
     * This method establishes the connection with the Router.
     *
     * @param routerSocketAddress Socket Address of the router
     * @param serverSocketAddress Socket Address of the server
     */
    private static void establishRouterConnection(SocketAddress routerSocketAddress, InetSocketAddress serverSocketAddress) throws Exception {

        //Open a Datagram Channel
        try (DatagramChannel clientRouterChannel = DatagramChannel.open()) {
            String clientMessage = "Hi Server, I am Client";
            sequenceNumber++;

            //Creating a Packet to send to the Router and setting the serverSocketAddress in the setPeerAddress
            Packet clientPacket = new Packet.Builder().setType(0).setSequenceNumber(sequenceNumber)
                    .setPortNumber(serverSocketAddress.getPort()).setPeerAddress(serverSocketAddress.getAddress())
                    .setPayload(clientMessage.getBytes()).create();

            //Sending the request to the Router
            clientRouterChannel.send(clientPacket.toBuffer(), routerSocketAddress);
            System.out.println("Sending Hi from Client");

            clientRouterChannel.configureBlocking(false);
            Selector selector = Selector.open();
            clientRouterChannel.register(selector, OP_READ);

            //setting the timeout for the client to receive the acknowledgement from the router
            selector.select(timeout);

            Set<SelectionKey> selectorKeySet = selector.selectedKeys();
            if (selectorKeySet.isEmpty()) {
                System.out.println("No response received after Timeout: " + timeout);
                System.out.println("Sending Request again to Establish Connection with Router");
                resendRequestToRouter(clientRouterChannel, clientPacket, routerSocketAddress);
            }

            //Setting max-size and order-type of the buffer
            ByteBuffer responseBuffer = ByteBuffer.allocate(Packet.MAX_LEN).order(ByteOrder.BIG_ENDIAN);

            //Extracting the payload from response from the Router
            Packet response = Packet.fromBuffer(responseBuffer);
            String payloadFromResponse = new String(response.getPayload(), StandardCharsets.UTF_8);
            System.out.println("Payload From Response RECEIVED: " + payloadFromResponse);
            receivedPackets.add(response.getSequenceNumber());
            selectorKeySet.clear();
        }
    }

    /**
     * This method will resend request to router if timeout occurs
     */
    private static void resendRequestToRouter(DatagramChannel clientRouterChannel, Packet clientPacket, SocketAddress routerSocketAddress) throws IOException {

        //Resending the request to the Router
        clientRouterChannel.send(clientPacket.toBuffer(), routerSocketAddress);
        System.out.println("RE-SENDING request to Router: \n" + new String(clientPacket.getPayload()));

        // Checking if the acknowledgement is received from the Router
        if (new String(clientPacket.getPayload()).equals("Received")) {
            acknowledgementCount++;
        }

        clientRouterChannel.configureBlocking(false);
        Selector selector = Selector.open();
        clientRouterChannel.register(selector, OP_READ);
        selector.select(timeout);

        Set<SelectionKey> selectorKeySet = selector.selectedKeys();
        if (selectorKeySet.isEmpty() && acknowledgementCount < 10) {
            System.out.println("No response received after Timeout: " + timeout);
            System.out.println("Sending Request again to Establish Connection with Router");
            resendRequestToRouter(clientRouterChannel, clientPacket, routerSocketAddress);
        }
    }

    /**
     * This method will send UDP request to router based on client input
     */
    private static void executeClientRequest(SocketAddress routerAddr, InetSocketAddress serverAddr, String msg)
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
                resendRequestToRouter(channel, p, routerAddr);
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
                    resendRequestToRouter(channel, pAck, router);
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
