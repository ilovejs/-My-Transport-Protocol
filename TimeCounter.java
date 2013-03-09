import java.io.IOException;
import java.net.DatagramPacket;
import java.util.Timer;
import java.util.TimerTask;

public class TimeCounter implements Runnable{
    Timer timer;
    String status;
    int delay = 0;
    mtp_sender sendobj;
    
    public TimeCounter(mtp_sender sendobj, String status , int miliseconds) {
    	delay = miliseconds;
        timer = new Timer();
        this.status = status;
        this.sendobj = sendobj;
    }
    
    public boolean cancel(){
    	timer.cancel();
    	return true;
    }
    
    class RemindTask extends TimerTask {
        public void run() {
            //retransmit unacknowledged segment with smallest sequence number
        	//will set sender's status field to time-up
        	//status = "timeup";
        	//send smallest sequence unACKed segment again
        	if(sendobj.sentpacketlist.size() != 0){
        		MyPacket thatpacket = sendobj.sentpacketlist.first();
        		System.out.println("Timer sent smallest sequnce unAcked segment **** seq: " + thatpacket.getSeqno());
        		try {
    				sendobj.socket.send(thatpacket.getPacket());
    			} catch (IOException e) {
    				e.printStackTrace();
    			}
        	}else{
        		System.out.println("close timer **********");
        		//TODO
        		sendobj.timecounter = null;
        	}
        	System.out.println("Sent queue : " + sendobj.sentpacketlist);
        	
//        	timer.cancel();
        	//start timer
        }
    }

	@Override
	public void run() {
		timer.schedule(new RemindTask(), delay);
    }
}