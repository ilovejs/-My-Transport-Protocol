import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Random;
import java.util.Timer;
import java.util.TreeSet;
/*
 * sender send port : 6000
 * 		 listen port: 6001
 */
import java.util.TimerTask;

public class mtp_sender {
	String host_ip;
	String receiver_ip;
	String file; 
	int MWS;
	int MSS;
	int timeout; 
	int seed; 
	float pdrop;
	String status = "";
	TimeCounter timecounter = null;
	long sendbase = 0L;
	TreeSet<MyPacket> sentpacketlist;
	DatagramSocket socket;
	Thread timerthread = null;
	
	public mtp_sender(String host_ip, String receiver_ip, String file,
			int mWS, int mSS, int timeout, float pdrop, int seed) {
		super();
		try { //TODO:CHANGE PORT
			socket = new DatagramSocket(6000);
		} catch (SocketException e) {
			e.printStackTrace();
		} //sender src
		this.host_ip = host_ip;
		this.receiver_ip = receiver_ip;
		this.file = file;
		MWS = mWS;
		MSS = mSS;
		this.timeout = timeout;
		this.pdrop = pdrop;
		this.seed = seed;
		this.sentpacketlist = new TreeSet<MyPacket>();
	}
	
	public Thread getTimerthread() {
		return timerthread;
	}

	public void setTimerthread(Thread timerthread) {
		this.timerthread = timerthread;
	}

	//localhost 6000 test1.txt 500 50 300 0.1 300
	public static void main(String[] args) throws Exception {
		String receiverIp = args[0];
		int receiverport = Integer.parseInt(args[1]);
		String filename = args[2];
		int mws = Integer.parseInt(args[3]);
		int mss = Integer.parseInt(args[4]);
		int timeout = Integer.parseInt(args[5]);
		float pdrop = Float.parseFloat(args[6]);
		int seed = Integer.parseInt(args[7]);
		
		mtp_sender sender = new mtp_sender("localhost", receiverIp, filename, mws, mss, timeout, pdrop, seed);
		//localhost, localhost, file1.txt, 500, 50, 0.1, 300
		InetAddress receiverip = InetAddress.getByName(receiverIp);
		int sendport = 6000;
		
		long client_isn = 8000L;
		long server_isn = 0L;

		//three way handshake
		byte[] synBuff= handshakePacket(sendport, 6001, client_isn, 0, "syn", mws,mss);//16 bytes
		
		DatagramPacket sendPacket = new DatagramPacket(synBuff, synBuff.length, receiverip, receiverport);
		sender.socket.send(sendPacket);
		writeLog("send SYN",sendPacket);
		
		//System.out.println("---sent 1st syn---");
		//showPacket(sendPacket);
		while(true){
			DatagramPacket recvpacket = new DatagramPacket(new byte[18], 18);//only receive 16 bytes, synack
			sender.socket.receive(recvpacket);
			writeLog("receive SYN ACK packet",recvpacket);

			server_isn = getseqno(recvpacket);
			String pt = getPacketType(recvpacket);
			//System.out.println("---received 2nd syn ack---");
			//showPacket(recvpacket);
		
			if(pt.equals("synack")){
				//send last handshake packet, 8001, 9001
				byte[] syn0 = handshakePacket(sendport, recvpacket.getPort(), client_isn + 1, server_isn + 1, "ack", mws,mss);
				DatagramPacket syn0Packet = new DatagramPacket(syn0, syn0.length, receiverip, receiverport);
				sender.socket.send(syn0Packet);
				writeLog("send last SYN packet",syn0Packet);

				//System.out.println("has sent 3rd syn 0");
				//but no printing anything
				break;
			}			
		}//end of three way handshake
		//send file name,make it look like ast
		//TODO
		byte[] astbuf = astPacket(sendport, receiverport, client_isn + 1, server_isn + 1,filename);
		DatagramPacket astPacket = new DatagramPacket(astbuf, astbuf.length, receiverip, receiverport);
		sender.socket.send(astPacket);

		long minseqno = client_isn + 1;
		//start sending file
		//split file
		File file = new File(filename);
		double filesizeD = Double.valueOf(file.length());
		int totalPacketNumber = (int)Math.ceil(filesizeD / mss);
//		totalPacketNumber -= 1;
		System.out.println("total:" + totalPacketNumber);
		long nextseqno = client_isn + 1;  //from 8001
		sender.sendbase = client_isn +1;
		server_isn += 1; //from 9001
	
		//start sub thread
		ReceiveAck receiveThread = new ReceiveAck(sender.socket, sender, sender.sentpacketlist,sender.sendbase,sender.timecounter);
		//run thread
		Thread rcvthread = new Thread(receiveThread);
		System.out.println("=====start receive thread======");
		rcvthread.start();
		System.out.println("===============================");
		Thread.sleep(2 * 1000);
		sender.status = "send";
		int sendCounter = 0;
		
		int dcounter = 1;
		
		while(true){
			if(sender.status.equals("send")){
				//send first group of 5 packet 
				int CAP = mws/mss; //5 in a row
				for (int i = 0; i < CAP; i++) {
					sendCounter++;
					byte[] rawpiece = null;
					if(sendCounter == totalPacketNumber ){
						System.out.println("sendCounter" + sendCounter);
						RandomAccessFile f = new RandomAccessFile(file, "r");
						byte[] allcontent = new byte[(int) f.length()];
						int specialsize = allcontent.length - ((totalPacketNumber-1) * mss);
						rawpiece = getfilebuffer(file,(int)(nextseqno - client_isn - 1), specialsize);
					}else if(sendCounter < totalPacketNumber ){
						System.out.println("sendCounter" + sendCounter);
						rawpiece = getfilebuffer(file, (int)(nextseqno - client_isn - 1), mss);
					}if(sendCounter > totalPacketNumber ){
						Thread.sleep(3000);
						//send last packet
						//send terminated packet to user
				    	byte[] buf = endPacket(sendport,receiverport, nextseqno, server_isn);
						DatagramPacket dpacket = new DatagramPacket(buf,buf.length,receiverip,receiverport);
						sender.socket.send(dpacket);
						rcvthread.stop();
						sender.socket.close();
						System.exit(0);
					}
					byte[] dbuff = dataPacket(sendport,receiverport, nextseqno, server_isn, rawpiece);
					DatagramPacket datapacket = new DatagramPacket(dbuff,dbuff.length,receiverip,receiverport);
					System.out.println("---------data packet:-----seq: " + nextseqno);
					printData(rawpiece);
					System.out.println("---------end data packet--\n");
					//create a new copy
					MyPacket mp = new MyPacket(nextseqno, server_isn, datapacket);
					//simulate delay and packet loss
					Random random = new Random(sender.seed);
					
					 if(sender.timecounter == null){
					    	//mtp_sender sendobj, String status , int miliseconds
					    	sender.timecounter = new TimeCounter(sender,sender.status, timeout); //initial signal
					    	System.out.println("~~~~~~~~~~~~~~~~~~start timer~~~~~~~~~~~~~~ " + timeout + " status: " + sender.status);
					    	sender.timerthread = new Thread(sender.timecounter);
					    	sender.timerthread.start();
//					    	sender.timecounter.run();
					}
					
					//drop
					if (random.nextFloat() < pdrop) {
						System.out.println("xxxxxxxxxxxxxxxx Reply not sent xxxxxxxxxxxxx");
						//pass by that sequence number
						nextseqno = nextseqno + sender.MSS;
						//add everything even it's lost
						sender.sentpacketlist.add(mp); //ordered by sequence number
						continue; 
					}
//					if(dcounter <= 2){
//						System.out.println("xxxxxxxxxxxxxxxx Reply not sent xxxxxxxxxxxxx");
//						//pass by that sequence number
//						nextseqno = nextseqno + sender.MSS;
//						dcounter++;
//						//add everything even it's lost
//						sender.sentpacketlist.add(mp); //ordered by sequence number
//						continue;
//					}
					
					sender.sentpacketlist.add(mp); //ordered by sequence number
					//tell it's receiving component to start listen
					sender.socket.send(datapacket);
					writeLog("sent data packet",datapacket);
				    //change to something not send
				    
				   

				    //update next sequence number
				    nextseqno = nextseqno + sender.MSS;
				    if(sendCounter == totalPacketNumber){
				    	Thread.sleep(3 * 1000);
				    	//send terminated packet to user
				    	byte[] buf = endPacket(sendport,receiverport, nextseqno, server_isn);
						DatagramPacket dpacket = new DatagramPacket(buf,buf.length,receiverip,receiverport);
						sender.socket.send(dpacket);
						
						rcvthread.stop();
						sender.socket.close();
						System.exit(0);
				    }
				}//end for
				//System.out.println("2222222222222");
				//waiting for timeout or ACK received
				sender.status = "wait";
				System.out.println("main thread status :" + sender.status);
			}//end if
			int cc = 0;
			if(cc++ == 20){
				System.out.println("FFFFFFFFF" + sender.status);
			}
			
		}
	}//main
	
	//Time, Event, Packet Header(data)
	private static void writeLog(String event, DatagramPacket pack) throws IOException{
		SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
		long timeInMillis = System.currentTimeMillis();
		String tstr = formatter.format(new Date(timeInMillis));
		String packtype = getPacketType(pack);
		String data = null;
		
		byte[] allcontent = pack.getData();
		byte[] datacontent = new byte[allcontent.length - 18];
		//write file
		FileWriter fstream = new FileWriter("mtp_sender.log",true);
		BufferedWriter out = new BufferedWriter(fstream);
				
		if(packtype.equals("syn")){
			//print header
			datacontent = null;
			out.write(tstr + " " + event + "\n");
		}else if(packtype.equals("ack")){
			//print header
//			header = new byte[18];
//			byte[] allcontent = pack.getData();
//			for(int i = 0; i < 18; i++){
//				header[i] = allcontent[i];
//			}
			out.write(tstr + " " + event + "\n");
		}else if(packtype.equals("fin")){
			//print header
			out.write(tstr + " " + event + "\n");
		}else if(packtype.equals("synack")){// 01000010
			//print header
			out.write(tstr + " " + event + "\n");
		}else if(packtype.equals("ackpsh")){// 01000010
			
			//print header and content
			datacontent = new byte[allcontent.length - 18];
			for(int j = 18; j < allcontent.length; j++){
				datacontent[j-18] = allcontent[j];
			}
			long seqno = getseqno(pack);
			long ackno = getackno(pack);
			
			ByteArrayInputStream bais = new ByteArrayInputStream(datacontent);
			InputStreamReader isr = new InputStreamReader(bais);
			BufferedReader br = new BufferedReader(isr);
			String s;
			StringBuilder sb = new StringBuilder();
			while((s = br.readLine()) != null){
				sb.append(s);
			}
			data = sb.toString();
			out.write(tstr + " " + event + ": " + seqno + " " + ackno + " " + " =====> " + data + "\n");
			bais.close();
			isr.close();
		}
		out.close();
	}
	
	private static byte[] endPacket(int srcPort, int destPort, long seqno, long ackno){
		byte[] p = new byte[18]; //18 bytes header
		p[0] = (byte) (srcPort >>> 8);  //src high
		p[1] = (byte) (srcPort >>> 0);
		p[2] = (byte) (destPort >>> 8);
		p[3] = (byte) (destPort >>> 0);
		p[4] = (byte)(seqno >>> 32);
		p[5] = (byte)(seqno >>> 16);
		p[6] = (byte)(seqno >>> 8);
		p[7] = (byte)(seqno >>> 0);
		p[8] = (byte)(ackno >>> 32);
		p[9] = (byte)(ackno >>> 16);
		p[10] = (byte)(ackno >>> 8);
		p[11] = (byte)(ackno >>> 0);
		p[12] = (byte)(0 >>> 0);
		//flag FIN
		p[13] = (byte)(1 >>> 0); 
		//windows size
		p[14] = (byte)(0 >>> 8);
		p[15] = (byte)(0 >>> 0);
		p[16] = (byte)(0 >>> 8);
		p[17] = (byte)(0 >>> 0);
		return p;
	}

	public TimeCounter getTimecounter() {
		return timecounter;
	}

	public void setTimecounter(TimeCounter timecounter) {
		this.timecounter = timecounter;
	}

	public String getStatus() {
		return status;
	}
	
	public void setStatus(String status) {
		this.status = status;
	}
	
	private static byte[] dataPacket(int srcPort, int destPort, long seqno, long ackno, byte[] data) {
		byte[] p = new byte[data.length + 18]; //18 bytes header
		p[0] = (byte) (srcPort >>> 8);  //src high
		p[1] = (byte) (srcPort >>> 0);
		p[2] = (byte) (destPort >>> 8);
		p[3] = (byte) (destPort >>> 0);
		p[4] = (byte)(seqno >>> 32);
		p[5] = (byte)(seqno >>> 16);
		p[6] = (byte)(seqno >>> 8);
		p[7] = (byte)(seqno >>> 0);
		p[8] = (byte)(ackno >>> 32);
		p[9] = (byte)(ackno >>> 16);
		p[10] = (byte)(ackno >>> 8);
		p[11] = (byte)(ackno >>> 0);
		p[12] = (byte)(0 >>> 0);
		//flag ACK and PSH
		p[13] = (byte)(96 >>> 0); //01100000
		//windows size
		p[14] = (byte)(0 >>> 8);
		p[15] = (byte)(0 >>> 0);
		p[16] = (byte)(0 >>> 8);
		p[17] = (byte)(0 >>> 0);
		for(int i = 0; i < data.length; i++){
			p[i + 18] = data[i];
		}
		return p;
	}
	
	private static byte[] astPacket(int srcPort, int destPort, long seqno, long ackno,String filename) {
		byte[] bb = filename.getBytes();
		byte[] p = new byte[bb.length + 18]; //18 bytes header
		p[0] = (byte) (srcPort >>> 8);  //src high
		p[1] = (byte) (srcPort >>> 0);
		p[2] = (byte) (destPort >>> 8);
		p[3] = (byte) (destPort >>> 0);
		p[4] = (byte)(seqno >>> 32);
		p[5] = (byte)(seqno >>> 16);
		p[6] = (byte)(seqno >>> 8);
		p[7] = (byte)(seqno >>> 0);
		p[8] = (byte)(ackno >>> 32);
		p[9] = (byte)(ackno >>> 16);
		p[10] = (byte)(ackno >>> 8);
		p[11] = (byte)(ackno >>> 0);
		p[12] = (byte)(0 >>> 0);
		//ash
		p[13] = (byte)(4 >>> 0); 
		//windows size
		p[14] = (byte)(0 >>> 8);
		p[15] = (byte)(0 >>> 0);
		p[16] = (byte)(0 >>> 8);
		p[17] = (byte)(0 >>> 0);
		//19 byte
		for(int i=0; i < bb.length; i++){
			p[18 + i] = bb[i];
		}
		return p;
	}
	
	public static void showPacket(DatagramPacket request){
		String packetType = getPacketType(request);
		System.out.println("Type: " + packetType);
		long seqno = getseqno(request);
	    long ackno = getackno(request);
	    System.out.println("seq#: " + seqno);
	    System.out.println("ack#: " + ackno);
		// Print the recieved data.
	    showbyte(request.getData());
	}
	
	public static byte[] handshakePacket(int srcPort, int destPort, long seqno, long ackno, String flag, int mws, int mss){
		byte[] p = new byte[18];
		p[0] = (byte) (srcPort >>> 8);  //src high
		p[1] = (byte) (srcPort >>> 0);
		p[2] = (byte) (destPort >>> 8);
		p[3] = (byte) (destPort >>> 0);
		p[4] = (byte)(seqno >>> 32);
		p[5] = (byte)(seqno >>> 16);
		p[6] = (byte)(seqno >>> 8);
		p[7] = (byte)(seqno >>> 0);
		p[8] = (byte)(ackno >>> 32);
		p[9] = (byte)(ackno >>> 16);
		p[10] = (byte)(ackno >>> 8);
		p[11] = (byte)(ackno >>> 0);
		p[12] = (byte)(0 >>> 0);
		if(flag.equals("syn")){
			p[13] = (byte)(2 >>> 0); //00000010
		}else if(flag.equals("ack")){
			p[13] = (byte)(64 >>> 0); //01000000
		}else if(flag.equals("fin")){
			p[13] = (byte)(1 >>> 0); //00000001
		}else if(flag.equals("synack")){// 01000010
			p[13] = (byte) (66 >>> 0);
		}else if(flag.equals("ast")){
			p[13] = (byte)(4 >>> 0); //00000100
		}
		//windows size
		p[14] = (byte)(mws >>> 8);
		p[15] = (byte)(mws >>> 0);
		p[16] = (byte)(mss >>> 8);
		p[17] = (byte)(mss >>> 0);
		//TODO:add data
		return p;
	}
	
	public static byte[] setSeq(long seq){
		byte[] f = new byte[4];
		String r = Long.toBinaryString(seq);
		String tmp = "";
		for (int i = 0; i < 32 - r.length(); i++) {
			tmp = tmp + "0";
		}
		r = tmp + r;
		for (int i = 0; i < 4; i++) {
			f[i] = Byte.parseByte(r.substring(i*8, (i+1)*8), 2);
//			System.out.println(Integer.toBinaryString(f[i]));
		}
		return f;
	}
	
	//set position + 1 th bit
	public static byte setBit(byte b, int position, int value){
		if(value == 1)
			b = (byte) (b & ~(1 << position));
		if(value == 0)
			b = (byte) (b & (1 << position));
		return b;
	}
	
	//size: length of byte array
	public static byte[] inttobyte(int value, int size) {
	    byte[] b = new byte[size];
	    for (int i = 0; i < size; i++) {
	        int offset = (b.length - 1 - i) * 8;
	        b[i] = (byte) ((value >>> offset) & 0xFF);
	    }
	    return b;
	}
	
	public static void showbyte(byte[] filepart){
		int c = 0;
		
		for (byte b : filepart) {
			//promote to int, treat all number as unsigned number
			int ival = (b & 0xff);
			String t = Integer.toBinaryString(ival);
			int len = t.length();
			for (int i = 0; i < 8 - len; i++) {
				t = "0" + t;
			}
			if(c % 4 == 0 && c!= 0)
				System.out.print("\n");
			c++;
			System.out.print(t + "--");
		}
		System.out.print("\n");
	}
	
	/*
	 * @param seq 	start byte
	 * @param mss 	length of data
	 */
//	public static byte[] getfilebuffer(File file, int seq, int mss) throws IOException{
//		byte[] r = new byte[mss];
//		RandomAccessFile f = new RandomAccessFile(file, "r");
//		byte[] b = new byte[(int)f.length()];
//		f.read(b);
//		for(int i = seq; i < mss + seq; i++){
//			r[i-seq] = b[i];
//		}
//		return r;
//	}
	public static byte[] getfilebuffer(File file, int s, int len)
			throws IOException {
		byte[] r = new byte[len];
		RandomAccessFile f = new RandomAccessFile(file, "r");
		byte[] allcontent = new byte[(int) f.length()];
		f.read(allcontent);
		for (int i = 0; i < len; i++) {
			r[i] = allcontent[i + s];
		}
		return r;
	}
	
	private static String getPacketType(DatagramPacket request) {
		String r = new String();
		byte[] buf = request.getData();
		// the 14th byte
		byte b13 = buf[13];
		if(		   ((b13 & (1 << 6)) != 0) 
				&& ((b13 & (1 << 5)) != 0) ){
			r = "ackpsh";
		}else if (       ((b13 & (1 << 6)) != 0) 
				&& ((b13 & (1 << 1)) != 0) ) {
//			System.out.println("synack");
			r = "synack";
		}else if ((b13 & (1 << 6)) != 0) {
//			System.out.println("ack");
			r = "ack";
		}else if ((b13 & (1 << 0)) != 0) {
			r = "fin";
//			System.out.println("fin");
		}else if ((b13 & (1 << 1)) != 0) {
			r = "syn";
//			System.out.println("syn");
		}
		return r;
	}
	
	private static long getackno(DatagramPacket request) {
		long sn = 0L;
		byte[] raw = request.getData();
		byte[] buf = new byte[]{ raw[8], raw[9], raw[10], raw[11] };
		for (int i = 0; i < 4; i++) {
		    sn <<= 8;
		    sn |= (long)buf[i] & 0xFF;
		}
		return sn;
	}

	private static long getseqno(DatagramPacket request) {
		long sn = 0L;
		byte[] raw = request.getData();
		byte[] buf = new byte[]{ raw[4], raw[5], raw[6], raw[7] };
		for (int i = 0; i < 4; i++) {
		    sn <<= 8;
		    sn |= (long)buf[i] & 0xFF;
		}
		return sn;
	}
	
	private static void printData(byte[] buf) throws Exception {
		ByteArrayInputStream bais = new ByteArrayInputStream(buf);
		InputStreamReader isr = new InputStreamReader(bais);
		BufferedReader br = new BufferedReader(isr);
		String line = br.readLine();
		System.out.println(line);
	}
}