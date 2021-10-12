/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/GUIForms/JFrame.java to edit this template
 */
package demo_videostream;

import java.awt.Image;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Random;
import java.util.StringTokenizer;
import javax.swing.ImageIcon;
import javax.swing.Timer;
/**
 *
 * @author thien
 */
public class Client extends javax.swing.JFrame {
    ImageIcon icon;
    /**
     * Creates new form Client
     */
    public Client() {
        timer = new Timer(20, new Client.timerListener());
        timer.setInitialDelay(0);
        timer.setCoalesce(true);

        //init RTCP packet sender
        rtcpSender = new Client.RtcpSender(400);

        //allocate enough memory for the buffer used to receive data from the server
        buf = new byte[15000];

        //create the frame synchronizer
        fsynch = new Client.FrameSynchronizer(100);
        initComponents();
    }
//RTP variables:
    //----------------
    DatagramPacket rcvdp;            //UDP packet received from the server
    DatagramSocket RTPsocket;        //socket to be used to send and receive UDP packets
    static int RTP_RCV_PORT = 25000; //port where the client will receive the RTP packets


    Timer timer; //timer used to receive data from the UDP socket
    byte[] buf;  //buffer used to store data received from the server

    //RTSP variablesNothing to read
    //----------------
    //rtsp states
    final static int INIT = 0;
    final static int READY = 1;
    final static int PLAYING = 2;
    static int state;            //RTSP state == INIT or READY or PLAYING
    Socket RTSPsocket;           //socket used to send/receive RTSP messages
    InetAddress ServerIPAddr;

    //input and output stream filters
    static BufferedReader RTSPBufferedReader;
    static BufferedWriter RTSPBufferedWriter;
    static String VideoFileName; //video file to request to the server
    int RTSPSeqNb = 0;           //Sequence number of RTSP messages within the session
    String RTSPid;              // ID of the RTSP session (given by the RTSP Server)

    final static String CRLF = "\r\n";
    final static String DES_FNAME = "session_info.txt";

    //RTCP variables
    //----------------
    DatagramSocket RTCPsocket;          //UDP socket for sending RTCP packets
    static int RTCP_RCV_PORT = 19001;   //port where the client will receive the RTP packets
    static int RTCP_PERIOD = 400;       //How often to send RTCP packets
    RtcpSender rtcpSender;

    //Video constants:
    //------------------
    static int MJPEG_TYPE = 26; //RTP payload type for MJPEG video

    //Statistics variables:
    //------------------
    double statDataRate;        //Rate of video data received in bytes/s
    int statTotalBytes;         //Total number of bytes received in a session
    double statStartTime;       //Time in milliseconds when start is pressed
    double statTotalPlayTime;   //Time in milliseconds of video playing since beginning
    float statFractionLost;     //Fraction of RTP data packets from sender lost since the prev packet was sent
    int statCumLost;            //Number of packets lost
    int statExpRtpNb;           //Expected Sequence number of RTP messages within the session
    int statHighSeqNb;          //Highest sequence number received in session
    FrameSynchronizer fsynch;
    
    //------------------------------------
    //Handler for timer
    //------------------------------------
    class timerListener implements ActionListener {

        public void actionPerformed(ActionEvent e) {

            //Construct a DatagramPacket to receive data from the UDP socket
            rcvdp = new DatagramPacket(buf, buf.length);

            try {
                //receive the DP from the socket, save time for stats
                RTPsocket.receive(rcvdp);

                double curTime = System.currentTimeMillis();
                statTotalPlayTime += curTime - statStartTime;
                statStartTime = curTime;

                //create an RTPpacket object from the DP
                RTPpacket rtp_packet = new RTPpacket(rcvdp.getData(), rcvdp.getLength());
                int seqNb = rtp_packet.getsequencenumber();

                //this is the highest seq num received

                //print important header fields of the RTP packet received:
                System.out.println("Got RTP packet with SeqNum # " + seqNb
                        + " TimeStamp " + rtp_packet.gettimestamp() + " ms, of type "
                        + rtp_packet.getpayloadtype());

                //print header bitstream:
                rtp_packet.printheader();

                //get the payload bitstream from the RTPpacket object
                int payload_length = rtp_packet.getpayload_length();
                byte [] payload = new byte[payload_length];
                rtp_packet.getpayload(payload);

                //compute stats and update the label in GUI
                statExpRtpNb++;
                if (seqNb > statHighSeqNb) {
                    statHighSeqNb = seqNb;
                }
                if (statExpRtpNb != seqNb) {
                    statCumLost++;
                }
                statDataRate = statTotalPlayTime == 0 ? 0 : (statTotalBytes / (statTotalPlayTime / 1000.0));
                statFractionLost = (float)statCumLost / statHighSeqNb;
                statTotalBytes += payload_length;


                //get an Image object from the payload bitstream
                Toolkit toolkit = Toolkit.getDefaultToolkit();
                fsynch.addFrame(toolkit.createImage(payload, 0, payload_length), seqNb);

                //display the image as an ImageIcon object
                icon = new ImageIcon(fsynch.nextFrame());
                iconLabel.setIcon(icon);

            }
            catch (InterruptedIOException iioe) {
                System.out.println("338- Nothing to read");
//                iioe.printStackTrace();
            }
            catch (IOException ioe) {
                System.out.println("Exception caught: "+ioe);
            }
        }
    }

    //------------------------------------
    // Send RTCP control packets for QoS feedback
    //------------------------------------
    class RtcpSender implements ActionListener {

        private Timer rtcpTimer;
        int interval;

        // Stats variables
        private int numPktsExpected;    // Number of RTP packets expected since the last RTCP packet
        private int numPktsLost;        // Number of RTP packets lost since the last RTCP packet
        private int lastHighSeqNb;      // The last highest Seq number received
        private int lastCumLost;        // The last cumulative packets lost
        private float lastFractionLost; // The last fraction lost

        Random randomGenerator;         // For testing only

        public RtcpSender(int interval) {
            this.interval = interval;
            rtcpTimer = new Timer(interval, this);
            rtcpTimer.setInitialDelay(0);
            rtcpTimer.setCoalesce(true);
            randomGenerator = new Random();
        }

        public void run() {
            System.out.println("RtcpSender Thread Running");
        }

        public void actionPerformed(ActionEvent e) {

            // Calculate the stats for this period
            numPktsExpected = statHighSeqNb - lastHighSeqNb;
            numPktsLost = statCumLost - lastCumLost;
            lastFractionLost = numPktsExpected == 0 ? 0f : (float)numPktsLost / numPktsExpected;
            lastHighSeqNb = statHighSeqNb;
            lastCumLost = statCumLost;

            //To test lost feedback on lost packets
            // lastFractionLost = randomGenerator.nextInt(10)/10.0f;

            RTCPpacket rtcp_packet = new RTCPpacket(lastFractionLost, statCumLost, statHighSeqNb);
            int packet_length = rtcp_packet.getlength();
            byte[] packet_bits = new byte[packet_length];
            rtcp_packet.getpacket(packet_bits);

            try {
                DatagramPacket dp = new DatagramPacket(packet_bits, packet_length, InetAddress.getByName("localhost"), RTCP_RCV_PORT);
                RTCPsocket.send(dp);
            } catch (InterruptedIOException iioe) {
                System.out.println("396- Nothing to read");
            } catch (IOException ioe) {
                System.out.println("Exception caught: "+ioe);
            }
        }

        // Start sending RTCP packets
        public void startSend() {
            rtcpTimer.start();
        }

        // Stop sending RTCP packets
        public void stopSend() {
            rtcpTimer.stop();
        }
    }

    //------------------------------------
    //Synchronize frames
    //------------------------------------
    class FrameSynchronizer {

        private ArrayDeque<Image> queue;
        private int bufSize;
        private int curSeqNb;
        private Image lastImage;

        public FrameSynchronizer(int bsize) {
            curSeqNb = 1;
            bufSize = bsize;
            queue = new ArrayDeque<Image>(bufSize);
        }

        //synchronize frames based on their sequence number
        public void addFrame(Image image, int seqNum) {
            if (seqNum < curSeqNb) {
                queue.add(lastImage);
            }
            else if (seqNum > curSeqNb) {
                for (int i = curSeqNb; i < seqNum; i++) {
                    queue.add(lastImage);
                }
                queue.add(image);
            }
            else {
                queue.add(image);
            }
        }

        //get the next synchronized frame
        public Image nextFrame() {
            curSeqNb++;
            lastImage = queue.peekLast();
            return queue.remove();
        }
    }

    //------------------------------------
    //Parse Server Response
    //------------------------------------
    private int parseServerResponse() {
        int reply_code = 0;

        try {
            //parse status line and extract the reply_code:
            String StatusLine = RTSPBufferedReader.readLine();
            System.out.println("RTSP Client - Received from Server:");
            System.out.println(StatusLine);

            StringTokenizer tokens = new StringTokenizer(StatusLine);
            tokens.nextToken(); //skip over the RTSP version
            reply_code = Integer.parseInt(tokens.nextToken());

            //if reply code is OK get and print the 2 other lines
            if (reply_code == 200) {
                String SeqNumLine = RTSPBufferedReader.readLine();
                System.out.println(SeqNumLine);

                String SessionLine = RTSPBufferedReader.readLine();
                System.out.println(SessionLine);

                tokens = new StringTokenizer(SessionLine);
                String temp = tokens.nextToken();
                //if state == INIT gets the Session Id from the SessionLine
                if (state == INIT && temp.compareTo("Session:") == 0) {
                    RTSPid = tokens.nextToken();
                }
                else if (temp.compareTo("Content-Base:") == 0) {
                    // Get the DESCRIBE lines
                    String newLine;
                    for (int i = 0; i < 6; i++) {
                        newLine = RTSPBufferedReader.readLine();
                        System.out.println(newLine);
                    }
                }
            }
        } catch(Exception ex) {
            System.out.println("Exception caught: "+ex);
            System.exit(0);
        }

        return(reply_code);
    }


    //------------------------------------
    //Send RTSP Request
    //------------------------------------

    private void sendRequest(String request_type) {
        try {
            //Use the RTSPBufferedWriter to write to the RTSP socket

            //write the request line:
            RTSPBufferedWriter.write(request_type + " " + VideoFileName + " RTSP/1.0" + CRLF);

            //write the CSeq line:
            RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);

            //check if request_type is equal to "SETUP" and in this case write the
            //Transport: line advertising to the server the port used to receive
            //the RTP packets RTP_RCV_PORT
            if (request_type == "SETUP") {
                RTSPBufferedWriter.write("Transport: RTP/UDP; client_port= " + RTP_RCV_PORT + CRLF);
            }
            else if (request_type == "DESCRIBE") {
                RTSPBufferedWriter.write("Accept: application/sdp" + CRLF);
            }
            else {
                //otherwise, write the Session line from the RTSPid field
                RTSPBufferedWriter.write("Session: " + RTSPid + CRLF);
            }

            RTSPBufferedWriter.flush();
        } catch(Exception ex) {
            System.out.println("Exception caught: "+ex);
            System.exit(0);
        }
    }
    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jPanel1 = new javax.swing.JPanel();
        jPanel2 = new javax.swing.JPanel();
        jPanel3 = new javax.swing.JPanel();
        jSlider1 = new javax.swing.JSlider();
        btnSetup = new javax.swing.JLabel();
        btnPlay = new javax.swing.JLabel();
        btnPause = new javax.swing.JLabel();
        btnTeardown = new javax.swing.JLabel();
        iconLabel = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);

        jPanel1.setBackground(new java.awt.Color(27, 27, 27));

        javax.swing.GroupLayout jPanel1Layout = new javax.swing.GroupLayout(jPanel1);
        jPanel1.setLayout(jPanel1Layout);
        jPanel1Layout.setHorizontalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 209, Short.MAX_VALUE)
        );
        jPanel1Layout.setVerticalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 528, Short.MAX_VALUE)
        );

        jPanel2.setBackground(new java.awt.Color(27, 27, 27));

        javax.swing.GroupLayout jPanel2Layout = new javax.swing.GroupLayout(jPanel2);
        jPanel2.setLayout(jPanel2Layout);
        jPanel2Layout.setHorizontalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 0, Short.MAX_VALUE)
        );
        jPanel2Layout.setVerticalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 56, Short.MAX_VALUE)
        );

        jPanel3.setBackground(new java.awt.Color(0, 0, 0));

        btnSetup.setIcon(new javax.swing.ImageIcon(getClass().getResource("/Image/list.png"))); // NOI18N
        btnSetup.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                btnSetupMouseClicked(evt);
            }
        });

        btnPlay.setIcon(new javax.swing.ImageIcon(getClass().getResource("/Image/play-button (1).png"))); // NOI18N
        btnPlay.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                btnPlayMouseClicked(evt);
            }
        });

        btnPause.setIcon(new javax.swing.ImageIcon(getClass().getResource("/Image/pause.png"))); // NOI18N
        btnPause.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                btnPauseMouseClicked(evt);
            }
        });

        btnTeardown.setIcon(new javax.swing.ImageIcon(getClass().getResource("/Image/stop.png"))); // NOI18N
        btnTeardown.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                btnTeardownMouseClicked(evt);
            }
        });

        javax.swing.GroupLayout jPanel3Layout = new javax.swing.GroupLayout(jPanel3);
        jPanel3.setLayout(jPanel3Layout);
        jPanel3Layout.setHorizontalGroup(
            jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel3Layout.createSequentialGroup()
                .addGap(447, 447, 447)
                .addComponent(btnSetup)
                .addGap(41, 41, 41)
                .addComponent(btnPlay)
                .addGap(34, 34, 34)
                .addComponent(btnPause)
                .addGap(35, 35, 35)
                .addComponent(btnTeardown)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel3Layout.createSequentialGroup()
                .addContainerGap(214, Short.MAX_VALUE)
                .addComponent(jSlider1, javax.swing.GroupLayout.PREFERRED_SIZE, 695, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(159, 159, 159))
        );
        jPanel3Layout.setVerticalGroup(
            jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel3Layout.createSequentialGroup()
                .addGap(21, 21, 21)
                .addComponent(jSlider1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addComponent(btnSetup)
                    .addComponent(btnPlay)
                    .addComponent(btnPause)
                    .addComponent(btnTeardown))
                .addContainerGap(46, Short.MAX_VALUE))
        );

        iconLabel.setText("jLabel5");

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jPanel2, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addGroup(layout.createSequentialGroup()
                .addComponent(jPanel1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(367, 367, 367)
                .addComponent(iconLabel)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
            .addComponent(jPanel3, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                .addComponent(jPanel2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jPanel1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(layout.createSequentialGroup()
                        .addGap(217, 217, 217)
                        .addComponent(iconLabel)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)))
                .addComponent(jPanel3, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(0, 0, Short.MAX_VALUE))
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void btnSetupMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_btnSetupMouseClicked
        // TODO add your handling code here:
        System.out.println("Setup Button pressed !");

        if (state == INIT) {
            //Init non-blocking RTPsocket that will be used to receive data
            try {
                //construct a new DatagramSocket to receive RTP packets from the server, on port RTP_RCV_PORT
                RTPsocket = new DatagramSocket(RTP_RCV_PORT);
                //UDP socket for sending QoS RTCP packets
                RTCPsocket = new DatagramSocket();
                //set TimeOut value of the socket to 5msec.
                RTPsocket.setSoTimeout(5);
            }
            catch (SocketException se)
            {
                System.out.println("Socket exception: "+se);
                System.exit(0);
            }

            //init RTSP sequence number
            RTSPSeqNb = 1;

            //Send SETUP message to the server
            sendRequest("SETUP");

            //Wait for the response
            if (parseServerResponse() != 200)
                System.out.println("Invalid Server Response");
            else
            {
                //change RTSP state and print new state
                state = READY;
                System.out.println("New RTSP state: READY");
            }
        }
        //else if state != INIT then do nothing
    }//GEN-LAST:event_btnSetupMouseClicked

    private void btnPlayMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_btnPlayMouseClicked
        // TODO add your handling code here:
        // TODO add your handling code here:
         System.out.println("Play Button pressed!");

        //Start to save the time in stats
        statStartTime = System.currentTimeMillis();

        if (state == READY) {
            //increase RTSP sequence number
            RTSPSeqNb++;

            //Send PLAY message to the server
            sendRequest("PLAY");

            //Wait for the response
            if (parseServerResponse() != 200) {
                System.out.println("Invalid Server Response");
            }
            else {
                //change RTSP state and print out new state
                state = PLAYING;
                System.out.println("New RTSP state: PLAYING");

                //start the timer
                timer.start();
                rtcpSender.startSend();
            }
        }
        //else if state != READY then do nothing
    }//GEN-LAST:event_btnPlayMouseClicked

    private void btnPauseMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_btnPauseMouseClicked
        // TODO add your handling code here:
        // TODO add your handling code here:
        System.out.println("Pause Button pressed!");

        if (state == PLAYING)
        {
            //increase RTSP sequence number
            RTSPSeqNb++;

            //Send PAUSE message to the server
            sendRequest("PAUSE");

            //Wait for the response
            if (parseServerResponse() != 200)
                System.out.println("Invalid Server Response");
            else
            {
                //change RTSP state and print out new state
                state = READY;
                System.out.println("New RTSP state: READY");

                //stop the timer
                timer.stop();
                rtcpSender.stopSend();
            }
        }
        //else if state != PLAYING then do nothing
    }//GEN-LAST:event_btnPauseMouseClicked

    private void btnTeardownMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_btnTeardownMouseClicked
        // TODO add your handling code here:
        // TODO add your handling code here:
        System.out.println("Teardown Button pressed !");

        //increase RTSP sequence number
        RTSPSeqNb++;

        //Send TEARDOWN message to the server
        sendRequest("TEARDOWN");

        //Wait for the response
        if (parseServerResponse() != 200)
            System.out.println("Invalid Server Response");
        else {
            //change RTSP state and print out new state
            state = INIT;
            System.out.println("New RTSP state: INIT");

            //stop the timer
            timer.stop();
            rtcpSender.stopSend();

            //exit
            System.exit(0);
        }
    }//GEN-LAST:event_btnTeardownMouseClicked

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        /* Set the Nimbus look and feel */
        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
        /* If Nimbus (introduced in Java SE 6) is not available, stay with the default look and feel.
         * For details see http://download.oracle.com/javase/tutorial/uiswing/lookandfeel/plaf.html 
         */
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ClassNotFoundException ex) {
            java.util.logging.Logger.getLogger(Client.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (InstantiationException ex) {
            java.util.logging.Logger.getLogger(Client.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (IllegalAccessException ex) {
            java.util.logging.Logger.getLogger(Client.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (javax.swing.UnsupportedLookAndFeelException ex) {
            java.util.logging.Logger.getLogger(Client.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        }
        //</editor-fold>
        //</editor-fold>
        Client theClient = new Client();

        //get server RTSP port and IP address from the command line
        //------------------
        try {
            int RTSP_server_port = 1051;
            String ServerHost = "localhost";
            theClient.ServerIPAddr = InetAddress.getByName(ServerHost);

            //get video filename to request:
            VideoFileName = "movie.Mjpeg";

            //Establish a TCP connection with the server to exchange RTSP messages
            //------------------
            theClient.RTSPsocket = new Socket(theClient.ServerIPAddr, RTSP_server_port);

            //Establish a UDP connection with the server to exchange RTCP control packets
            //------------------

            //Set input and output stream filters:
            RTSPBufferedReader = new BufferedReader(new InputStreamReader(theClient.RTSPsocket.getInputStream()));
            RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(theClient.RTSPsocket.getOutputStream()));

            //init RTSP state:
            state = INIT;
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        /* Create and display the form */
        java.awt.EventQueue.invokeLater(new Runnable() {
            public void run() {
                new Client().setVisible(true);
            }
        });
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JLabel btnPause;
    private javax.swing.JLabel btnPlay;
    private javax.swing.JLabel btnSetup;
    private javax.swing.JLabel btnTeardown;
    private javax.swing.JLabel iconLabel;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JSlider jSlider1;
    // End of variables declaration//GEN-END:variables
}
