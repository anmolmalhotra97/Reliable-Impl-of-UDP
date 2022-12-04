import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.util.*;

/**
 * This class contains the implementation of UDP Server.
 */
public class Server {

    private static int statusCode = 200;
    static boolean debugFlag = false;
    static String defaultDirectory = System.getProperty("user.dir");
    static List<String> fileList = new ArrayList<>();
    static File currentFolder;
    public static int serverPort = 8080;


    public static void main(String[] args) {
        String request;
        System.out.print("Please start your server : ");

        request = getInputFromUser();
        List<String> serverStartRequestSplitList = Arrays.asList(request.split(" "));

        //Setting the debug flag
        if (serverStartRequestSplitList.contains("-v")) {
            debugFlag = true;
        }

        //setting the port number
        if (serverStartRequestSplitList.contains("-p")) {
            String portStr = serverStartRequestSplitList.get(serverStartRequestSplitList.indexOf("-p") + 1).trim();
            serverPort = Integer.parseInt(portStr);
        }

        //setting USER PROVIDED directory as default directory
        if (serverStartRequestSplitList.contains("-d"))
            defaultDirectory = request.substring(request.indexOf("-d") + 3);

        System.out.println("\nServer's Current Working Directory : " + defaultDirectory);

        if (debugFlag)
            System.out.println("Server is now running at port " + serverPort);

        currentFolder = new File(defaultDirectory);

        Server server = new Server();
        Runnable task = () -> {
            try {
                server.ListenAndServeIncomingClientRequests(serverPort);
            } catch (Exception e) {
                e.printStackTrace();
            }
        };
        Thread thread = new Thread(task);
        thread.start();
    }

    private static String getInputFromUser() {
        String request;
        Scanner serverInputScanner = new Scanner(System.in);
        request = serverInputScanner.nextLine();
        if (request.isEmpty()) {
            System.out.println("Invalid Command entered, try again.");
        }
        return request;
    }

    /**
     * This method will extract payload from client request
     */
    private void ListenAndServeIncomingClientRequests(int serverPort) throws Exception {
        //will open datagram channel that will receive packets on port 8080
        try (DatagramChannel serverRouterDatagramChannel = DatagramChannel.open()) {
            serverRouterDatagramChannel.bind(new InetSocketAddress(serverPort));
            Packet responsePacketFromServer;
            ByteBuffer buf = ByteBuffer.allocate(Packet.MAXIMUM_BYTES_FOR_UDP_REQUEST).order(ByteOrder.BIG_ENDIAN);

            while (true) {
                buf.clear();

                //The receive() method will copy the content of a received packet of data into the given Buffer.
                SocketAddress router = serverRouterDatagramChannel.receive(buf);

                if (router != null) {
                    // Parse a packet from the received raw data.
                    buf.flip();
                    Packet incomingPacketFromRouter = Packet.fromBuffer(buf);
                    buf.flip();

                    String requestPayloadFromClient = new String(incomingPacketFromRouter.getPayload(), UTF_8);

                    /*
                     * Send the response to the router not the client.
                     * The peer address of the packet is the address of the client already.
                     * We can use toBuilder to copy properties of the current packet.
                     * This demonstrates how to create a new packet from an existing packet.
                     */
                    // Sending Hello message to client if the request payload is a hello greeting from client
                    if (requestPayloadFromClient.equals("Hi Server, I am Client")) {
                        System.out.println("Payload from Client : " + requestPayloadFromClient);
                        responsePacketFromServer = incomingPacketFromRouter.toBuilder().setPayload("Hi Client, I'm Server. Nice To meet you!!".getBytes()).create();
                        serverRouterDatagramChannel.send(responsePacketFromServer.toBuffer(), router);
                        System.out.println("Sending Hi from Server");
                    }
                    //checking if the request is of the type htpfs or httpc and execute accordingly
                    else if (requestPayloadFromClient.contains("httpfs") || requestPayloadFromClient.contains("httpc")) {
                        System.out.println("Payload from Client : " + requestPayloadFromClient);
                        String responsePayload = processPayloadRequest(requestPayloadFromClient);

                        //We can't send the data since it exceeds the maximum size of the packet
                        if (responsePayload.getBytes().length > Packet.MAXIMUM_BYTES_FOR_UDP_REQUEST) {
                            responsePacketFromServer = incomingPacketFromRouter.toBuilder().setPayload("Data size exceeds allowed limit".getBytes()).create();
                        }
                        //If the repsonse payload is within the size limit then we prepare the response accordingly and send it to the client
                        else {
                            responsePacketFromServer = incomingPacketFromRouter.toBuilder().setPayload(responsePayload.getBytes()).create();
                        }

                        serverRouterDatagramChannel.send(responsePacketFromServer.toBuffer(), router);

                    }
                    // Checking if the request is actually an ACKNOWLEDGMENT request
                    else if (requestPayloadFromClient.equals("Received")) {
                        System.out.println("Client: " + requestPayloadFromClient + "\nSending Close");
                        responsePacketFromServer = incomingPacketFromRouter.toBuilder().setPayload("Close".getBytes()).create();
                        serverRouterDatagramChannel.send(responsePacketFromServer.toBuffer(), router);

                    }
                    // Checking if client sent a connection termination request
                    else if (requestPayloadFromClient.equals("Ok")) {
                        System.out.println("Client: " + requestPayloadFromClient);
                        System.out.println(requestPayloadFromClient + " received..!");

                    }
                }
            }
        }

    }

    /**
     * This method processes the payload request from the client's input and will return the response body.
     *
     * @param request client's request
     * @return response body
     */
    private String processPayloadRequest(String request) throws Exception {

        String clientUrl = "";
        String response;
        String verboseBody = "";
        boolean verbose = false;
        //checking overwrite flag
        boolean overwrite = !request.contains("-overwrite=false");

        List<String> clientRequestSplitList = Arrays.asList(request.split(" "));

        if (debugFlag)
            System.out.println("Server is processing Payload Request");


        for (String clientRequest : clientRequestSplitList) {
            if (clientRequest.startsWith("http://"))
                clientUrl = clientRequest;
        }

        if (clientUrl.contains(" ")) {
            clientUrl = clientUrl.split(" ")[0];
        }

        String host = new URI(clientUrl).getHost();

        if (clientRequestSplitList.contains("-v"))
            verbose = true;

        StringBuilder body = new StringBuilder("{\n");
        body.append("\t\"args\":");
        body.append("{},\n");
        body.append("\t\"headers\": {");


        for (int i = 0; i < clientRequestSplitList.size(); i++) {
            if (clientRequestSplitList.get(i).equals("-h")) {

                String t1 = clientRequestSplitList.get(i + 1).split(":")[0];
                String t2 = clientRequestSplitList.get(i + 1).split(":")[1];
                body.append("\n\t\t\"").append(t1).append("\": \"").append(t2).append("\",");
            }
        }


        body.append("\n\t\t\"Connection\": \"close\",\n");
        body.append("\t\t\"Host\": \"").append(host).append("\"\n");


        body.append("\t},\n");

        // GET or POST
        String requestType;

        if (clientUrl.endsWith("get/"))
            requestType = "GetFilesList";
        else if (clientUrl.contains("get"))
            requestType = "GetFileContent";
        else
            requestType = "POST";


        //Get list of files in the current directory
        switch (requestType) {
            case "GetFilesList": {

//            parentDirectory = currentFolder;
                fileList = new ArrayList<>();
                body.append("\t\"files\": { ");
                List<String> files = getFilesFromDir(currentFolder, fileList);

                //Can use files directly
                List<String> fileFilterList = new ArrayList<String>(files);

                for (int i = 0; i < fileFilterList.size() - 1; i++) {
                    body.append(files.get(i)).append(" ,\n\t\t\t    ");
                }

                body.append(fileFilterList.get(fileFilterList.size() - 1)).append(" },\n");
                statusCode = 200;

                break;
            }


            // Get file content of the requested file
            case "GetFileContent": {
                String fileContent = "";

                String requestedFile;
                requestedFile = clientUrl.substring(clientUrl.indexOf("get/") + 4);

                List<String> files = getFilesFromDir(currentFolder, fileList);
                System.out.println("Final file list:" + files);


                if (!files.contains(requestedFile)) {
                    statusCode = 404;
                } else {

                    File file = new File(defaultDirectory + "/" + requestedFile);
                    fileContent = readDataFromFile(file);
                    body.append("\t\"data\": \"").append(fileContent).append("\",\n");

                    statusCode = 200;
                }


                break;
            }

            // Post request
            case "POST": {

                String requestedFile;
                StringBuilder data = new StringBuilder();

                requestedFile = clientUrl.substring(clientUrl.indexOf("post/") + 5);
                List<String> files = getFilesFromDir(currentFolder, fileList);


                if (!files.contains(requestedFile))
                    statusCode = 202;
                else
                    statusCode = 201;

                int index = clientRequestSplitList.indexOf("-d");

                for (int i = index + 1; i < clientRequestSplitList.size(); i++) {
                    //System.out.println(data + " data is this");
                    if (!clientRequestSplitList.get(i).equals("-overwrite=false"))
                        data.append(clientRequestSplitList.get(i)).append(" ");
                }


                File file = new File(defaultDirectory + "/" + requestedFile);
                writeResponseToFile(file, data.toString(), overwrite);

                break;
            }
        }

        if (statusCode == 200) {
            body.append("\t\"status\": \"").append("HTTP/1.1 200 OK").append("\",\n");
        } else if (statusCode == 201) {
            if (overwrite)
                body.append("\t\"status\": \"").append("HTTP/1.1 201 FILE WAS OVER-WRITTEN").append("\",\n");
            else
                body.append("\t\"status\": \"").append("HTTP/1.1 201 FILE WAS NOT OVER-WRITTEN").append("\",\n");
        } else if (statusCode == 202) {
            body.append("\t\"status\": \"").append("HTTP/1.1 202 NEW FILE CREATED").append("\",\n");
        } else if (statusCode == 404) {
            body.append("\t\"status\": \"").append("HTTP/1.1 404 FILE NOT FOUND").append("\",\n");
        }


        body.append("\t\"origin\": \"").append(InetAddress.getLocalHost().getHostAddress()).append("\",\n");
        body.append("\t\"url\": \"").append(clientUrl).append("\"\n");
        body.append("}\n");


        if (verbose) {
            verboseBody = verboseBody + "HTTP/1.1 200 OK\n";
            verboseBody = verboseBody + "Date: " + java.util.Calendar.getInstance().getTime() + "\n";
            verboseBody = verboseBody + "Content-Type: application/json\n";
            verboseBody = verboseBody + "Content-Length: " + body.length() + "\n";
            verboseBody = verboseBody + "Connection: close\n";
            verboseBody = verboseBody + "Server: Localhost\n";
            verboseBody = verboseBody + "Access-Control-Allow-Origin: *\n";
            verboseBody = verboseBody + "Access-Control-Allow-Credentials: true\n";

            response = verboseBody;
            response = response + body;
        } else {
            response = body.toString();
        }


        if (debugFlag) {
            System.out.println("Sending response to Client..");

        }

        System.out.println(clientUrl);
        System.out.println("response size:" + response.getBytes().length);
        System.out.println("response:" + response);
        return response;
    }

    /**
     * This method will give list of files from specific directory
     *
     * @return List of files
     */
    static private List<String> getFilesFromDir(File currentDir, List<String> filelist) {

        if (currentDir == null) {
            return null;
        }

        for (File file : Objects.requireNonNull(currentDir.listFiles())) {
            if (!file.isDirectory()) {
                if (!Arrays.asList(Objects.requireNonNull(currentFolder.listFiles())).contains(file)) {
                    file.setExecutable(false);
                    file.setReadable(false);
                    file.setWritable(false);
                }
                if (!filelist.contains(file.getName()))
                    filelist.add(file.getName());
            } else {
                if (!filelist.contains(file.getName()))
                    filelist.add(file.getName());

                //System.out.println("dir is recursive" + filelist);
                getFilesFromDir(file, filelist);
            }
            //to list the files and the subdirectories, but the packet size is an issue!!!
            // filelist.add(file.getName());
        }
        return filelist;
    }


    static public void writeResponseToFile(File fileName, String data, boolean flagOverwrite) {
        try {
            BufferedWriter bufferedWriter;

            if (flagOverwrite) {
                System.out.println("Over writing is set to true");
                bufferedWriter = new BufferedWriter(new FileWriter(fileName));
            } else {
                System.out.println("Appending the data");
                bufferedWriter = new BufferedWriter(new FileWriter(fileName, true));
            }
            bufferedWriter.write(data);
            bufferedWriter.close();

            if (debugFlag)
                System.out.println("Response successfully saved to " + fileName);

        } catch (IOException ex) {
            if (debugFlag)
                System.out.println("Error while writing to file : '" + fileName + "'" + ex);
        }
    }


    static public String readDataFromFile(File fileName) {
        StringBuilder lines = new StringBuilder("");
        String line = null;

        try {

            if (!fileName.canRead()) {
                lines.append("Unable to read the file");

            } else {
                BufferedReader bufferedReader = new BufferedReader(new FileReader(fileName));

                while ((line = bufferedReader.readLine()) != null) {
                    lines.append(line);

                }
                bufferedReader.close();
            }
        } catch (IOException ex) {
            if (debugFlag)
                System.out.println("Error reading file : '" + fileName + "'" + ex);
        }

        return lines.toString();
    }
}
