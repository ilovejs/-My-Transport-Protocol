import java.net.DatagramPacket;
public class MyPacket implements Comparable<MyPacket>{
	DatagramPacket packet;
	long seqno;
	long ackno;
	
	public MyPacket(long seqno, long ackno, DatagramPacket p){
		packet = p;
		this.seqno = seqno;
		this.ackno = ackno;
	}

	@Override
	public String toString() {
		return "MyPacket [seqno=" + seqno + ", ackno=" + ackno + "]";
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (int) (ackno ^ (ackno >>> 32));
		result = prime * result + (int) (seqno ^ (seqno >>> 32));
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		MyPacket other = (MyPacket) obj;
		if (ackno != other.ackno)
			return false;
		if (seqno != other.seqno)
			return false;
		return true;
	}

	public DatagramPacket getPacket() {
		return packet;
	}

	public void setPacket(DatagramPacket packet) {
		this.packet = packet;
	}

	public long getSeqno() {
		return seqno;
	}

	public void setSeqno(long seqno) {
		this.seqno = seqno;
	}

	public long getAckno() {
		return ackno;
	}

	public void setAckno(long ackno) {
		this.ackno = ackno;
	}

	@Override
	public int compareTo(MyPacket o) {
		if(this.getSeqno() < o.getSeqno()){
			return -1;
		}else if(this.getSeqno() == o.getSeqno()){
			return 0;
		}else {
			return 1;
		}
	}
}
