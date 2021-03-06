package jxna.audio.modules;

import java.io.*;
import jxna.audio.*;
import jxna.audio.manipulation.Mapping;



public class Organya{
	public static final Mapping logMap=new Mapping(){public double getValue(double x){return Math.pow(10,x-1);}};
	
	private static byte[][] melody,drums;
	private static int percSampleRate;
	private static final int freqDivisor=256;
	
	private double clickLen;
	private int loopPoint,songLen;
	private int[] instruments=new int[16],tracksizes=new int[16];
	private int[][] data;
	private boolean[] pi=new boolean[16];
	private double[] freqoff=new double[16];
	
	public static void loadSamples(InputStream resStream) throws IOException{
		//read sample data in from the resource file
		int mqty=resStream.read();
		int mlen=0;
		for(int i=0;i<3;i++){mlen*=256;mlen+=resStream.read();}
		melody=new byte[mqty][mlen];
		for(byte[] b:melody) resStream.read(b);
		drums=new byte[resStream.read()][];
		percSampleRate=256*resStream.read();
		percSampleRate+=resStream.read();
		for(int i=0;i<drums.length;i++){
			mlen=0;
			for(int j=0;j<3;j++){mlen*=256;mlen+=resStream.read();}
			drums[i]=new byte[mlen];
			resStream.read(drums[i]);
		}
		resStream.close();
	}
	
	private static int unsign(byte b){
		if(b<0) return b+256;
		return b;
	}
	
	//this code loads the data from the org file
	public Organya(InputStream orgStream) throws IOException{
		//ignore the first 6 bytes of the org file
		orgStream.skip(6);
		
		//an array to temporarily store small chunks of data from the org file
		byte[] stuff=new byte[12];
		
		//read 12 bytes of data into the array
		orgStream.read(stuff);
		
		//get the wait value (clickLen), start point (loopPoint), and end point (songLen)
		clickLen=(unsign(stuff[0])+256*stuff[1])/1000.0;
		loopPoint=unsign(stuff[4])+256*stuff[5];
		songLen=unsign(stuff[8])+256*stuff[9];
		
		//read track data
		for(int i=0;i<16;i++){
			//read and process the "freq" value
			int freq=orgStream.read();
			freq+=orgStream.read()*256;
			freqoff[i]=(freq-1000.0)/freqDivisor;
			
			instruments[i]=orgStream.read();
			orgStream.read(stuff,0,3);
			tracksizes[i]=unsign(stuff[1])+256*stuff[2];
			pi[i]=stuff[0]>0;
		}
		
		//read event data
		data=new int[16][songLen];
		for(int i=0;i<16;i++){
			int volume=0,hold=0,pan=0;
			
			//tracksizes[i] is the number of events (resources) for track i
			for(int j=0;j<tracksizes[i];j++){
				//read the time that the event occurs
				orgStream.read(stuff,0,4);
				int time=unsign(stuff[0])+256*stuff[1];
				
				//put a "marker" in the data array indicating that there is an event there
				if(time<songLen) data[i][time]=1;
			}
			
			//read all resource data for this track into the resdata array
			//4 bytes per resource: note, duration, volume, pan
			byte[] resdata=new byte[tracksizes[i]*4];
			orgStream.read(resdata);
			
			//index keeps track of which resource is next to be processed
			int index=0;
			
			//for each "click" in the song
			for(int j=0;j<songLen;j++){
				int note=255;
				
				//if this track has a resource at this position in the song
				if(data[i][j]==1){
					//store the 4 bytes for this resource into the stuff array 
					for(int k=0;k<4;k++) stuff[k]=resdata[index+tracksizes[i]*k];
					
					index++;
					
					//for note, volume, and pan, a value of 255 indicates no change
					
					//if the note changes, set the value of hold to the duration,
					//and mark that the sound should be re-triggered at this point
					note=unsign(stuff[0]);
					if(note<255) hold=unsign(stuff[1]);
					
					//get the volume and pan values
					int v=unsign(stuff[2]);
					if(v<255) volume=v;
					int p=unsign(stuff[3]);
					if(p<255) pan=p;
				}
				
				//the variable hold keeps track of how much longer the note needs to be held
				//I use the note value 256 to indicate the note release
				if(note==255 && hold>0){hold--;}
				if(hold==0) note=256;
				
				//store the note, volume, and pan into the data array
				data[i][j]=65536*note+256*volume+pan;
			}
		}
		orgStream.close();
	}
	
	public DSP getSound(boolean logVol,boolean shelfPan){return getSound(logVol,logMap,shelfPan);}
	
	public DSP getSound(final boolean logVol,final Mapping pan,final boolean shelfPan){
		final double panMul=1/pan.getValue(shelfPan? 1:.5);
		return new DSP(2){
			private double clickPos=0;
			private int click=0;
			private int[] periodsLeft=new int[16],pointqty=new int[8];
			private boolean[] tactive=new boolean[16],makeEven=new boolean[8];
			private double[] tfreq=new double[16],tpos=new double[16],lvol=new double[16],rvol=new double[16];
			
			protected void advance(double smpSecondsElapsed,double seqSecondsElapsed){
				clickPos+=seqSecondsElapsed;
				while(clickPos>=clickLen){
					clickPos-=clickLen;
					
					//for each track
					for(int j=0;j<16;j++){
						//get the note, volume, and pan values for this track at this click
						int tvolume=(data[j][click]%65536)/256;
						int note=data[j][click]/65536;
						double tpan=(data[j][click]%256-6)/6.0;
						double tvol=tvolume/255.0;
						if(logVol) tvol=Math.pow(10,tvol-1);
						lvol[j]=rvol[j]=tvol;
						if(shelfPan){
							if(tpan<0) rvol[j]*=pan.getValue(1+tpan)*panMul;
							if(tpan>0) lvol[j]*=pan.getValue(1-tpan)*panMul;
						}else{
							rvol[j]*=pan.getValue((1+tpan)/2)*panMul;
							lvol[j]*=pan.getValue((1-tpan)/2)*panMul;
						}
						
						if(note==256 && j<8) tactive[j]=false;
						if(note<255){
							if(pi[j]){
								periodsLeft[j]=4;
								for(int i=11;i<note;i+=12) periodsLeft[j]+=4;
							}
							tactive[j]=true;
							tpos[j]=0.0;
							double foff=freqoff[j];
							for(int k=24;k<=note;k+=12) if(k!=36) foff*=2;
							tfreq[j]=j<8? 440.0*Math.pow(2.0,(note-45)/12.0)+foff:note*percSampleRate;
							if(j<8){
								pointqty[j]=1024;
								for(int i=11;i<note;i+=12) pointqty[j]/=2;
								makeEven[j]=pointqty[j]<=256;
							}
						}
					}
					
					//increment click
					//check to see if we've reached the end of the song, and loop back if so
					if(++click==songLen) click=loopPoint;
				}
				
				output[0]=output[1]=0.0;
				for(int j=0;j<16;j++){
					if(tactive[j]){
						int ins=instruments[j];
						double samp1,samp2,pos=tpos[j];
						if(j<8){
							int size=pointqty[j];
							pos*=size;
							int pos1=(int)pos;
							pos-=pos1;
							int pos2=pos1+1;
							if(pos2==size) pos2=0;
							if(makeEven[j]){pos1-=pos1%2;pos2-=pos2%2;}
							samp1=melody[ins][(pos1*256)/size];
							samp2=melody[ins][(pos2*256)/size];
						}else{
							int pos1=(int)pos;
							pos-=pos1;
							byte[] drum=drums[ins];
							samp1=drum[pos1++];
							samp2=pos1<drum.length? drum[pos1]:0;
						}
						
						//do interpolation
						double samp=(samp1+pos*(samp2-samp1))/128;
						
						//multiply the sample frame by the left and right volume, and add it to the output
						output[0]+=lvol[j]*samp;
						output[1]+=rvol[j]*samp;
						
						tpos[j]+=tfreq[j]*smpSecondsElapsed;
						while(tpos[j]>=1.0 && j<8 && tactive[j]){
							tpos[j]--;
							if(pi[j]) if(--periodsLeft[j]==0) tactive[j]=false;
						}
						if(j>=8) if(tpos[j]>=drums[ins].length) tactive[j]=false;
					}
				}
			}
		};
	}
}
