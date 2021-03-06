import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.nio.*;
import java.util.zip.*;

public class FileSender{
	final static int THREADNUM = 100;
	final static int BUFFERSIZE = 401;
	
	static InetSocketAddress addr;
	static int port;
	static DatagramSocket sk;
	static FileInputStream input;
	static File file;
	static Semaphore filemutex;
	static Semaphore countmutex;
	static Semaphore respmutex;
	static int packetNumber;
	static int[] ackresp = new int [BUFFERSIZE];
	static int[] threadFinished = new int [BUFFERSIZE];
	static boolean allSenderFinished;
	static boolean timeNotOut0 = true;
	static boolean fileEnd = false;
	static int fileEndACK;
	//receiver
	static class receiver implements Runnable{
		byte[] resp;
		ByteBuffer respb;
		DatagramPacket ack;
		CRC32 crc;
		int sequenceNumber;
		long nck;
		public void run(){
			try {
				sk.setSoTimeout(20);
			} catch (SocketException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			//System.out.println("receiver starting...");
			while(!allSenderFinished){
				resp = new byte[100];
				respb = ByteBuffer.wrap(resp);
				ack = new DatagramPacket(resp, resp.length);
				crc = new CRC32();
				try {
					sk.receive(ack);
					//System.out.println("receiveing...");
				} catch (SocketTimeoutException e) {
					// TODO Auto-generated catch block
					//e.printStackTrace();
					continue;
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				if (ack.getLength() < 8)
				{
					//System.out.println("Pkt too short");
					continue;
				}
				respb.rewind();
				long chksum = respb.getLong();
				crc.reset();
				crc.update(resp, 8, ack.getLength() - 8);
				if(chksum == crc.getValue()){
					sequenceNumber = respb.getInt();
					//System.out.println("receive ack:" + sequenceNumber);
					//System.out.println("receive ack while ackresp[0] = :" + ackresp[0]);
					try {
						respmutex.acquire();
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					if(sequenceNumber != -1){
						if(sequenceNumber < ackresp[0] || sequenceNumber > ackresp[0] + BUFFERSIZE - 2){
							respmutex.release();
							continue;
						}
						nck = respb.getLong();
						if(nck == 1){
							ackresp[sequenceNumber - ackresp[0] + 1] = 1;
						}
						else{
							ackresp[sequenceNumber - ackresp[0] + 1] = -1;
						}
					}
					else{
						nck = respb.getLong();
						if(nck == 1){
							fileEndACK = 1;
						}
						else{
							fileEndACK = -1;
						}
					}
					respmutex.release();
				}
				
			}
			//System.out.println("receiver ends.");
			System.exit(0);
		}
	}
	
	//sender
	static class sender implements Runnable{
		DatagramPacket pkt;
		byte[] data;
		ByteBuffer b;
		CRC32 crc;
		int num;
		int sequenceNumber;
		boolean getNextPck;
		boolean timeNotOut;
		class task extends TimerTask{
			public void run(){
				timeNotOut = false;
			}
		}
		public void run(){

			//System.out.println("Sender starting...");
			pkt = null;
			data = new byte[1000];
			b = ByteBuffer.wrap(data);
			crc = new CRC32();
			num = 0;
			sequenceNumber = 0;
			getNextPck = true;
			timeNotOut = true;
			while(true){
				if(getNextPck){
					try {
						filemutex.acquire();
					} catch (InterruptedException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
					try {
						data = new byte[1000];
						b = ByteBuffer.wrap(data);
						num = input.read(data, 16, data.length - 16);
					} catch (IOException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
					if(num != -1){
						try {
							countmutex.acquire();
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
						sequenceNumber = packetNumber;
						packetNumber++;
						countmutex.release();
					}
					filemutex.release();
					//System.out.println("num = " + num);
					if(num == -1 && fileEnd){
						int i;
						for(i = 0; i < THREADNUM; i++){
							if(threadFinished[i] == 0){
								break;
							}
						}
						threadFinished[i++] = 1;
						//System.out.println("thread finished: " + i);
						if(i == THREADNUM){
							allSenderFinished = true;
						}
						return;
					}
					else if(num != -1){
						b.rewind();
						b.putLong(0);
						b.putInt(sequenceNumber);
						b.putInt(num);
						//System.out.println("produce packet:" + sequenceNumber);
						crc.reset();
						crc.update(data, 8, data.length - 8);
						long chksum = crc.getValue();
						b.rewind();
						b.putLong(chksum);
					}
					else{
						fileEnd = true;
						//System.out.println("hello");
						sequenceNumber = -1;
						b.rewind();
						b.putLong(0);
						b.putInt(sequenceNumber);
						b.putInt(packetNumber - 1);
						crc.reset();
						crc.update(data, 8, data.length - 8);
						long chksum = crc.getValue();
						b.rewind();
						b.putLong(chksum);
					}
				}
				try {
					pkt = new DatagramPacket(data, data.length, addr);
					//System.out.println("send packet:" + sequenceNumber);
					sk.send(pkt);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				timeNotOut = true;
				Timer timer = new Timer();
				timer.schedule(new task(), 10);
				if(sequenceNumber == -1){
					while(timeNotOut && fileEndACK == 0){
						try {
							Thread.sleep(1);
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
					if(fileEndACK == 1){
						getNextPck = true;
					}
					else{
						getNextPck = false;
						fileEndACK = 0;
					}
				}
				else{
					try {
						respmutex.acquire();
					} catch (InterruptedException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
					int arrayIndex = sequenceNumber - ackresp[0] + 1;
					while(arrayIndex < BUFFERSIZE && arrayIndex > 0 && ackresp[arrayIndex] == 0 && timeNotOut){
						try {
							Thread.sleep(1);
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
					if(arrayIndex <= 0){
						getNextPck = true;
						respmutex.release();
						timer.cancel();
						continue;
					}
					else if(arrayIndex < BUFFERSIZE && ackresp[arrayIndex] == 1){
						//System.out.println(ackresp[0] + " and " + arrayIndex);
						getNextPck = true;
						int i;
						for(i = 1; i < BUFFERSIZE; i++){
							if(ackresp[i] == 1){
								ackresp[0]++;
							}
							else{
								break;
							}
						}
						for(int j = i; j < BUFFERSIZE; j++){
							ackresp[j - i + 1] = ackresp[j];
						}
						while(i > 1){
							ackresp[BUFFERSIZE + 1 - i] = 0;
							i--;
						}
					}
					else if(arrayIndex < BUFFERSIZE) {
						getNextPck = false;
						ackresp[arrayIndex] = 0;
					}
					else{
						getNextPck = false;
					}
					respmutex.release();
				}
				timer.cancel();
			}
		}
	}	
		
		
		

	//task0
	static class task0 extends TimerTask{
		public void run(){
			timeNotOut0 = false;
			//System.out.println("timeNotOut0 = " + timeNotOut0);
		}
	}
	
	
	//start
	public static void main(String[] args) throws Exception 
	{
		if (args.length != 4) {
			System.err.println("Usage: SimpleUDPSender <host name> <port number> <source file> <destination file name>");
			System.exit(-1);
		}
		port = Integer.parseInt(args[1]);
		addr = new InetSocketAddress(args[0], Integer.parseInt(args[1]));
		sk = new DatagramSocket();
		String fileName = args[2];
		String destinationFileName = args[3];
		file = new File(fileName);
		input = new FileInputStream(file);
		filemutex = new Semaphore(1, true);
		countmutex = new Semaphore(1, true);
		respmutex = new Semaphore(1, true);
		packetNumber = 0;
		for(int i = 0; i < BUFFERSIZE; i++){
			ackresp[i] = 0;
		}
		for(int i = 0; i < THREADNUM; i++){
			threadFinished[i] = 0;
		}
		allSenderFinished = false;
		fileEndACK = 0;
		DatagramPacket pkt;
		byte[] data = new byte[1000];
		ByteBuffer b = ByteBuffer.wrap(data);
		CRC32 crc = new CRC32();
		int sequenceNumber = 0;
		b.putLong(0);
		b.putInt(sequenceNumber);
		b.putInt(destinationFileName.length());
		for(int i = 0; i < destinationFileName.length(); i++){
			b.putChar(destinationFileName.charAt(i));
		}
		crc.reset();
		crc.update(data, 8, data.length - 8);
		long chksum = crc.getValue();
		b.rewind();
		b.putLong(chksum);
		Thread[] rec = new Thread[THREADNUM];
		for(int i = 0; i < THREADNUM; i++){
			rec[i] = new Thread(new receiver());
			rec[i].start();
		}
		while(true){
			//System.out.println("again==");
			pkt = new DatagramPacket(data, data.length, addr);
			sk.send(pkt);
			//System.out.println("send packet: 0");
			timeNotOut0 = true;
			Timer timer = new Timer();
			timer.schedule(new task0(), 10);
			int arrayIndex = sequenceNumber - ackresp[0] + 1;
			//System.out.println(sequenceNumber);
			//System.out.println(timeNotOut0);
			while(ackresp[arrayIndex] != 1 && timeNotOut0){
				Thread.sleep(1);
			}
			timer.cancel();
			if(ackresp[arrayIndex] == 1){
				int i;
				for(i = 1; i < BUFFERSIZE; i++){
					if(ackresp[i] == 1){
						ackresp[0]++;
					}
					else{
						break;
					}
				}
				for(int j = i; j < BUFFERSIZE; j++){
					ackresp[j - i + 1] = ackresp[j];
				}
				while(i > 1){
					ackresp[BUFFERSIZE + 1 - i] = 0;
					i--;
				}
				break;
			}
		}
		packetNumber++;
		Thread[] sen = new Thread[THREADNUM];
		for(int i = 0; i < THREADNUM; i++){
			sen[i] = new Thread (new sender());
			sen[i].start();
		}
	}

	final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
	public static String bytesToHex(byte[] bytes) {
	    char[] hexChars = new char[bytes.length * 2];
	    for ( int j = 0; j < bytes.length; j++ ) {
	        int v = bytes[j] & 0xFF;
	        hexChars[j * 2] = hexArray[v >>> 4];
	        hexChars[j * 2 + 1] = hexArray[v & 0x0F];
	    }
	    return new String(hexChars);
	}
}
