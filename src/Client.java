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

            //Skip the request if the user enters an empty string
            if (clientRequest == null)
                continue;


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
        Scanner clientInputScanner = new Scanner(System.in);
        request = clientInputScanner.nextLine();
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
        try (DatagramChannel clientRouterDatagramChannel = DatagramChannel.open()) {
            String clientMessage = "Hi Server, I am Client";
            sequenceNumber++;

            //Creating a Packet to send to the Router and setting the serverSocketAddress in the setPeerAddress
            Packet clientPacket = new Packet.Builder().setType(0).setSequenceNumber(sequenceNumber)
                    .setPortNumber(serverSocketAddress.getPort()).setPeerAddress(serverSocketAddress.getAddress())
                    .setPayload(clientMessage.getBytes()).create();

            //Sending the request to the Router
            clientRouterDatagramChannel.send(clientPacket.toBuffer(), routerSocketAddress);
            System.out.println("Sending Hi from Client");

            clientRouterDatagramChannel.configureBlocking(false);
            Selector selector = Selector.open();
            clientRouterDatagramChannel.register(selector, OP_READ);

            //setting the timeout for the client to receive the acknowledgement from the router
            selector.select(timeout);

            Set<SelectionKey> selectorKeySet = selector.selectedKeys();
            if (selectorKeySet.isEmpty()) {
                System.out.println("No response received after Timeout: " + timeout);
                System.out.println("Sending Request again to Establish Connection with Router");
                resendRequestToRouter(clientRouterDatagramChannel, clientPacket, routerSocketAddress);
            }

            //Setting max-size and order-type of the buffer
            ByteBuffer responseBuffer = ByteBuffer.allocate(Packet.MAXIMUM_BYTES_FOR_UDP_REQUEST).order(ByteOrder.BIG_ENDIAN);

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
    private static void resendRequestToRouter(DatagramChannel clientRouterDatagramChannel, Packet clientPacket, SocketAddress routerSocketAddress) throws IOException {

        //Resending the request to the Router
        clientRouterDatagramChannel.send(clientPacket.toBuffer(), routerSocketAddress);
        System.out.println("RE-SENDING request to Router: \n" + new String(clientPacket.getPayload()));

        // Checking if the acknowledgement is received from the Router
        if (new String(clientPacket.getPayload()).equals("Received")) {
            acknowledgementCount++;
        }

        clientRouterDatagramChannel.configureBlocking(false);
        Selector selector = Selector.open();
        clientRouterDatagramChannel.register(selector, OP_READ);
        selector.select(timeout);

        Set<SelectionKey> selectorKeySet = selector.selectedKeys();
        if (selectorKeySet.isEmpty() && acknowledgementCount < 10) {
            System.out.println("No response received after Timeout: " + timeout);
            System.out.println("Sending Request again to Establish Connection with Router");
            resendRequestToRouter(clientRouterDatagramChannel, clientPacket, routerSocketAddress);
        }
    }

    /**
     * This method will execute the client request and send a request via UDP to the server via the router
     */
    private static void executeClientRequest(SocketAddress routerSocketAddress, InetSocketAddress serverSocketAddress, String clientRequest)
            throws IOException {
        try (DatagramChannel clientRouterDatagramChannel = DatagramChannel.open()) {
            sequenceNumber++;

            Packet clientPacket = new Packet.Builder().setType(0).setSequenceNumber(sequenceNumber)
                    .setPortNumber(serverSocketAddress.getPort()).setPeerAddress(serverSocketAddress.getAddress())
                    .setPayload(clientRequest.getBytes()).create();
            clientRouterDatagramChannel.send(clientPacket.toBuffer(), routerSocketAddress);
            System.out.println("Client Request sent to the Router: \n" + new String(clientPacket.getPayload(), UTF_8));

            // Trying to receive the response from the server within the timeout period
            clientRouterDatagramChannel.configureBlocking(false);
            Selector selector = Selector.open();
            clientRouterDatagramChannel.register(selector, OP_READ);
            selector.select(timeout);

            Set<SelectionKey> selectorKeySet = selector.selectedKeys();
            if (selectorKeySet.isEmpty()) {
                System.out.println("No response received after Timeout: " + timeout);
                resendRequestToRouter(clientRouterDatagramChannel, clientPacket, routerSocketAddress);
            }

            // We just want a single response.
            ByteBuffer buf = ByteBuffer.allocate(Packet.MAXIMUM_BYTES_FOR_UDP_REQUEST).order(ByteOrder.BIG_ENDIAN);
            buf.flip();
            Packet response = Packet.fromBuffer(buf);
            String payload = new String(response.getPayload(), UTF_8);

            // Validate if we received a new sequence number in server response.
            if (!receivedPackets.contains(response.getSequenceNumber()))
                successfulResponseReceivedSendAcknowledgement(routerSocketAddress, serverSocketAddress, clientRouterDatagramChannel, buf, response, payload);
        }
    }

    private static void successfulResponseReceivedSendAcknowledgement(SocketAddress routerSocketAddress, InetSocketAddress serverSocketAddress, DatagramChannel clientRouterDatagramChannel, ByteBuffer buf, Packet response, String payload) throws IOException {
        Selector selector;
        Set<SelectionKey> selectorKeySet;
        // Adding the sequence number to the list of received packets in order to keep track of all the packets received.
        receivedPackets.add(response.getSequenceNumber());
        System.out.println("\nResponse received from the Server : \n" + payload);

        //Since we received a successful response from the server, now we need to send and ACK back to the server.
        // Hence, Incrementing the sequenceNumber
        sequenceNumber++;

        Packet acknowledgmentPacket = new Packet.Builder().setType(0).setSequenceNumber(sequenceNumber)
                .setPortNumber(serverSocketAddress.getPort()).setPeerAddress(serverSocketAddress.getAddress())
                .setPayload("Received".getBytes()).create();
        clientRouterDatagramChannel.send(acknowledgmentPacket.toBuffer(), routerSocketAddress);

        // Trying to receive the response from the server within the timeout period
        clientRouterDatagramChannel.configureBlocking(false);
        selector = Selector.open();
        clientRouterDatagramChannel.register(selector, OP_READ);
        selector.select(timeout);

        selectorKeySet = selector.selectedKeys();
        if (selectorKeySet.isEmpty()) {
            resendRequestToRouter(clientRouterDatagramChannel, acknowledgmentPacket, routerSocketAddress);
        }

        buf.flip();

        System.out.println("Connection terminated");
        selectorKeySet.clear();

        sequenceNumber++;
        Packet closeConnectionPacket = new Packet.Builder().setType(0).setSequenceNumber(sequenceNumber)
                .setPortNumber(serverSocketAddress.getPort()).setPeerAddress(serverSocketAddress.getAddress())
                .setPayload("Ok".getBytes()).create();
        clientRouterDatagramChannel.send(closeConnectionPacket.toBuffer(), routerSocketAddress);
        System.out.println("OK sent");
    }
}
