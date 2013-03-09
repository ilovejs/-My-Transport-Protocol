import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;
import java.util.TreeSet;

//
public class ReceiveAck implements Runnable{
	DatagramSocket socket;
	String status;
	TreeSet<MyPacket> sentpacketlist;
	long sendbase = 0L;
	int countOfDup = 0;
	TimeCounter timer;
	mtp_sender senderobj;
	String waitflag = "";
	
	ReceiveAck(DatagramSocket s, mtp_sender obj, TreeSet<MyPacket> sentpacketlist, long sendbase,TimeCounter timer){
		socket = s;
		this.timer = timer;
		this.status = obj.status;
		this.sentpacketlist = sentpacketlist;
		this.sendbase = sendbase;
		this.senderobj = obj;
	}
	@Override
	public void run() {
		while(true){
			System.out.print("\n");
			long ackno = 0L;
			//receive any packet, 20 header and 50 for segment
			//TODO: adaptive to arguments
			DatagramPacket recvpacket = new DatagramPacket(new byte[18], 18);
			try {
				socket.receive(recvpacket);
				//senderobj.status = "receivedack";
			} catch (IOException e) {
				e.printStackTrace();
			}
			ackno = getackno(recvpacket);
			
			String pt = getPacketType(recvpacket);

			if(pt.equals("ack")){
//				System.out.println("received ack no:" + ackno);
				//find packet by id, then delete
				try {
					writeLog("receive ack packet",recvpacket);
				} catch (IOException e1) {
					e1.printStackTrace();
				}
				if(ackno > sendbase){ //originally 8001
					System.out.println("Receive expected ACK");
					sendbase = ackno;
					//change send base
					senderobj.sendbase = ackno;
					
					MyPacket target = find(ackno - senderobj.MSS, sentpacketlist);//find seqno
					//delete from unACKed list
					//REMOVE
					sentpacketlist.remove(target);
					
					//there are currently not yet acknowledged segments
					if(sentpacketlist.size() > 0){
						System.out.println("There're unACKed segment");
						System.out.println(senderobj.sentpacketlist);
						//start timer
						//TODO:terminate main timer
						senderobj.timecounter.cancel();
						senderobj.getTimerthread().stop();
						//start a new one
						senderobj.timecounter = new TimeCounter(senderobj, senderobj.status, senderobj.timeout);
						Thread t = new Thread(senderobj.timecounter);
						senderobj.setTimerthread(t);
						senderobj.getTimerthread().start();
						System.out.println("Finished renew timer in ReACK.java");
					}//all packet has been ACKed and tell main thread to send again
					else if(sentpacketlist.size() == 0 && senderobj.status.equals("wait")){ //start
						//now you can sent again
						senderobj.timecounter.cancel();
						senderobj.timecounter = null;
						senderobj.getTimerthread().stop();
						senderobj.status = "send";

						System.out.println("----------------------------ReACK Has told Main to send again----------------------------");
					}
				}else{// y < send base means lost packet happens
					System.out.println("%%%% retransmit packet %%%%");
					countOfDup++;
					if(countOfDup == 3){
						//resent segment with sequence number y
						long ackno2 = getackno(recvpacket);
						System.out.println("duplicated ackno: " + ackno2);
						System.out.println("show sentPackList" + sentpacketlist);
						
						//ack number as seq no
						MyPacket thatpack = find(ackno2, sentpacketlist);
						Random random = new Random(senderobj.seed);
						//retransmit packet may also fallen
//						if (random.nextFloat() < senderobj.pdrop) {
//					        System.out.println("Reply not sent..");
//					        continue; 
//						}
					    //resent the packet
						try {
							socket.send(thatpack.getPacket());
							//set back
							countOfDup = 0;
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
			}			
		}//end of three way handshake
	}
	
	public MyPacket find(long seqno, TreeSet<MyPacket> sentpacketlist){
		MyPacket r = null;
		for (MyPacket p : sentpacketlist) {
			long sno = getseqno(p.getPacket());
			if(sno == seqno){
				return p;
			}
		}
		return r;
	}
	
	private static String getPacketType(DatagramPacket request) {
		String r = new String();
		byte[] buf = request.getData();
		// the 14th byte
		byte b13 = buf[13];
		
		if (       ((b13 & (1 << 6)) != 0) 
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
	
	private static int getdatasize(DatagramPacket p){
		int datasize = p.getData().length - 18;
		return datasize;
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
}
