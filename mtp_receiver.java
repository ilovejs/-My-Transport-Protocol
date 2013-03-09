import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Random;
import java.util.TreeSet;
/*
 * receiver send port: 6001
 * 		  listen port: 6000
 */
public class mtp_receiver {
	public static void main(String[] args) throws Exception {
		int sendport = 6001;
		DatagramSocket socket = new DatagramSocket(6001);
		long server_isn = 9000L;
		long mws = 0L;
		long mss = 0L;
		String filename = null;
		long sendbase = 0L;
		TreeSet<MyPacket> windows = new TreeSet<MyPacket>();
		long originalbase = 0L;
		while (true) {
			//default mtp mss is 536, plus header 20byte
			//TODO        //70
			DatagramPacket request = new DatagramPacket(new byte[68], 68);	
			socket.receive(request);
			System.out.println("---receive---");
			showPacket(request);
			System.out.println("-------------");
			// Send SYN ACK
			InetAddress clientHost = request.getAddress();
			int clientPort = request.getPort();
			String packetType = getPacketType(request);
			//server sequence number is 8000
			if(packetType.equals("syn")){
				mws = getmws(request);
				mss = getmss(request);
				System.out.println("mws:" + mws + "mss" + mss);
				//send ack syn
				byte[] synackpack = handshakePacket(sendport, request.getPort(), server_isn, getseqno(request) + 1, "synack",mws,mss);
				DatagramPacket synack = new DatagramPacket(synackpack, synackpack.length,clientHost, clientPort);
				writeLog("receive SYN packet",request);
				socket.send(synack);
				System.out.println("---sent syn ack---");
				writeLog("send SYN ACK", synack);
				//show
//				showPacket(synack);
			}else if (packetType.equals("ackpsh")){
				long sn = getseqno(request);
				long acn = getackno(request);
				MyPacket hpacket = new MyPacket(sn, acn, request);
				//add into windows buffer
				windows.add(hpacket);
				//response ack 
				//System.out.println("get data packet");
				byte[] datapart = getdata(request);
				////////////////////////
				printData(datapart);
				//System.out.println("\ndatapart leng" + request.getData().length);
				/////////////////////////
				System.out.println("\n");
				//file writer
				long seqno = getseqno(request); //seq#: 9001, ack#
				byte[] ackcontent;
				
				if(windows.size() == mws){ //5
					//windows is now full
					System.out.println("!! Windows full, send: " + (originalbase + mws + mss));
					ackcontent = ackPacket(sendport, request.getPort(), server_isn+1, originalbase + mws + mss);//datapart.length
					DatagramPacket pk = new DatagramPacket(ackcontent, ackcontent.length,clientHost, clientPort);
					socket.send(pk);
					writeLog("window full",pk);
				}
				
				//packet received is not expected
				if(seqno != sendbase){
					//repeating old sequence number
					ackcontent = ackPacket(sendport, request.getPort(), server_isn+1, sendbase);//datapart.length
					DatagramPacket pk = new DatagramPacket(ackcontent, ackcontent.length,clientHost, clientPort);
					//////////////////This is all right
					writeLog("receive ACKPSH packet",request);
					socket.send(pk);
					
					System.out.println("1send ACK back, ackno: " + sendbase);
					writeLog("response ACK back",pk);
				}else if(seqno == sendbase){  //match expected number
					//problematic ////////////////////
					writeData(datapart,filename);
					
					//cumulative ACK number 
					sendbase = seqno + mss;
					ackcontent = ackPacket(sendport, request.getPort(), server_isn+1, sendbase);//datapart.length
					DatagramPacket pk = new DatagramPacket(ackcontent, ackcontent.length,clientHost, clientPort);
					//write to log file
					writeLog("receive ACKPSH packet",request);
					socket.send(pk);

					System.out.println("2send ACK back, ackno: " + (seqno + mss));
					writeLog("response ACK back",pk);
				}
				
				
			}else if(packetType.equals("fin")){
				writeLog("receive FIN packet",request);
				//strin zero and weird char in file
				////////////////////
				cleanfile(filename);
				File f = new File("mtp_receiver.log");
				f.setWritable(true);
				cleanLog("mtp_receiver.log");
				socket.close();
				System.exit(0);
			}else if (packetType.equals("ast")){
				//get filename from itls
				filename = getfilename(request);
				//get initial sequence number from sender
				//TODO:chang it
				sendbase = 8001L;
				originalbase = sendbase;
			}
		}//end while
		
	}
	
	private static String getfilename(DatagramPacket request) throws IOException {
		String fn = null;
		//read byte 13,14
		byte[] data = request.getData();
		byte[] r = new byte[data.length - 18];
		//cut off header
		for(int i = 18; i < data.length - 1; i++){
			r[i-18] =  data[i];
		}
		ByteArrayInputStream bais = new ByteArrayInputStream(r);
		InputStreamReader isr = new InputStreamReader(bais);
		BufferedReader br = new BufferedReader(isr);
		String line;
		StringBuilder sb = new StringBuilder();
		while((line = br.readLine()) != null){
			sb.append(line);
		}
		fn = sb.toString();
		br.close();
		isr.close();
		bais.close();
		return fn;
	}

	private static void writeLog(String event, DatagramPacket pack) throws IOException{
		SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
		long timeInMillis = System.currentTimeMillis();
		String tstr = formatter.format(new Date(timeInMillis));
		String packtype = getPacketType(pack);
		String data = null;
		
		byte[] allcontent = pack.getData();
		byte[] datacontent = new byte[allcontent.length - 18];
		//write file
		FileWriter fstream = new FileWriter("mtp_receiver.log",true);
		BufferedWriter out = new BufferedWriter(fstream);
		long seqno = getseqno(pack);
		long ackno = getackno(pack);
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
			out.write(tstr + " " + event + ": " + seqno + " " + ackno + " " + " =====> " + data + "\n");
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
	
	private static byte[] getdata(DatagramPacket pack){
		byte[] allcontent = pack.getData();
		byte[] datacontent = new byte[allcontent.length - 18];
		datacontent = new byte[allcontent.length - 18];
		for(int j = 18; j < allcontent.length; j++){
			datacontent[j-18] = allcontent[j];
		}
		return datacontent;
	}
//	private static byte[] getdata(byte[] data){
//		//System.out.println(data.length);
//		byte[] r = new byte[data.length - 18];
//		//cut off header
//		for(int i = 18; i < data.length; i++){   //-1
//			r[i-18] =  data[i];
//		}
//		return r;
//	}
	
	private static byte[] ackPacket(int srcPort, int destPort, long seqno, long ackno) {
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
		//ack
		p[13] = (byte)(64 >>> 0); //01000000
		//windows size
		p[14] = (byte)(0 >>> 8);
		p[15] = (byte)(0 >>> 0);
		p[16] = (byte)(0 >>> 8);
		p[17] = (byte)(0 >>> 0);
		
		return p;
	}
	
	private static long getmws(DatagramPacket request) {
		long mss = 0L;
		byte[] buf = request.getData();
		mss <<= 8;
		mss |= (long)buf[14] & 0xFF;
		mss <<= 8;
		mss |= (long)buf[15] & 0xFF;
		return mss;
	}

	private static long getmss(DatagramPacket request) {
		long mss = 0L;
		byte[] buf = request.getData();
		mss <<= 8;
		mss |= (long)buf[16] & 0xFF;
		mss <<= 8;
		mss |= (long)buf[17] & 0xFF;
		return mss;
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

	public static int toInt(byte[] bytes, int offset) {
		  int ret = 0;
		  for (int i=0; i<4 && i+offset<bytes.length; i++) {
		    ret <<= 8;
		    ret |= (int)bytes[i] & 0xFF;
		  }
		  return ret;
	}
	
	private static String getPacketType(DatagramPacket request) {
		String r = new String();
		byte[] buf = request.getData();
		// the 14th byte
		byte b13 = buf[13];
		if(		   ((b13 & (1 << 6)) != 0) 
				&& ((b13 & (1 << 5)) != 0) ){
			r = "ackpsh";
		}else if (   ((b13 & (1 << 6)) != 0) 
				&& ((b13 & (1 << 1)) != 0) ) {
//			System.out.println("synack");
			r = "synack";
		}if (       ((b13 & (1 << 6)) != 0) 
				&& ((b13 & (1 << 5)) != 0) ) {
//			System.out.println("synack");
			r = "ackpsh";
		}else if ((b13 & (1 << 6)) != 0) {
//			System.out.println("ack");
			r = "ack";
		}else if ((b13 & (1 << 0)) != 0) {
			r = "fin";
//			System.out.println("fin");
		}else if ((b13 & (1 << 1)) != 0) {
			r = "syn";
//			System.out.println("syn");
		}else if((b13 & (1 << 2)) != 0){
			r = "ast";
		}
		return r;
	}

	private static void writeData(byte[] buf,String filename) throws Exception {
		String line = new String(buf);
		
		ByteArrayInputStream bais = new ByteArrayInputStream(buf);
		InputStreamReader isr = new InputStreamReader(bais);
		BufferedReader br = new BufferedReader(isr);
		//more than one line
//		String s;
//		StringBuilder sb = new StringBuilder();
//		while((s = br.readLine()) != null){
//			
//			sb.append(s);
//		}
//		
//		String line = sb.toString();
//		
		FileWriter fstream = new FileWriter(filename,true);
		BufferedWriter out = new BufferedWriter(fstream);
		
		out.write(line);
		out.close();
		fstream.close();
		br.close();
		isr.close();
		bais.close();
	}
	
	private static void cleanLog(String filename) throws IOException{
		File file = new File(filename);
		FileReader fr = new FileReader(file);
		BufferedReader br = new BufferedReader(fr); 
		String s = null;
		StringBuilder sb = new StringBuilder();
		while((s = br.readLine()) != null) { 
			 String n = s.replaceAll("\\00{2,}", "\n");
			 sb.append(n + "\n");
		} 
		FileWriter fstream = new FileWriter(filename);
		BufferedWriter out = new BufferedWriter(fstream);
		out.write(sb.toString());
		out.close();
	}
	
	private static void cleanfile(String filename) throws IOException, InterruptedException{

		File file = new File(filename);
		FileReader fr = new FileReader(file);
		BufferedReader br = new BufferedReader(fr); 
		String s = null;
		StringBuilder sb = new StringBuilder();
		while((s = br.readLine()) != null) { 
			 String n = s.replaceAll("\\00", "");
			 sb.append(n + "\n");
		} 
		FileWriter fstream = new FileWriter(filename);
		BufferedWriter out = new BufferedWriter(fstream);
		out.write(sb.toString());
		out.close();
	}
	
	private static void printData(byte[] buf) throws Exception {
		ByteArrayInputStream bais = new ByteArrayInputStream(buf);
		InputStreamReader isr = new InputStreamReader(bais);
		BufferedReader br = new BufferedReader(isr);
//		String line = br.readLine();
		String s;
		StringBuilder sb = new StringBuilder();
		while((s = br.readLine()) != null){
			sb.append(s);
		}
		String line = sb.toString();
		System.out.println(line);
//		System.out.println(line);
		br.close();
		isr.close();
		bais.close();
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
		System.out.println();
	}
	
	public static byte[] handshakePacket(int srcPort, int destPort, long seqno, long ackno, String flag, long mws, long mss){
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
		}
		//windows size
		p[14] = (byte)(mws >>> 8);
		p[15] = (byte)(mws >>> 0);
		p[16] = (byte)(mss >>> 8);
		p[17] = (byte)(mss >>> 0);
		//TODO:add data
		return p;
	}
}