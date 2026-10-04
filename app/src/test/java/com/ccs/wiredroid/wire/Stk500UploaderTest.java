package com.ccs.wiredroid.wire;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
public class Stk500UploaderTest {
 private static class Bootloader implements Stk500Uploader.Transport{
  byte[] flash=new byte[32768],response;int pointer,read;boolean corrupt,wrongSignature;int pages;
  public void reset(){}public void drain(){}
  public void write(byte[] command)throws IOException{int code=command[0]&255;byte[] payload=new byte[0];if(code==0x75)payload=wrongSignature?new byte[]{0,0,0}:new byte[]{30,(byte)149,15};
   if(code==0x55)pointer=((command[1]&255)|((command[2]&255)<<8))*2;
   if(code==0x64){int n=((command[1]&255)<<8)|(command[2]&255);System.arraycopy(command,4,flash,pointer,n);pages++;}
   if(code==0x74){int n=((command[1]&255)<<8)|(command[2]&255);payload=Arrays.copyOfRange(flash,pointer,pointer+n);if(corrupt)payload[0]^=1;}
   response=new byte[payload.length+2];response[0]=0x14;System.arraycopy(payload,0,response,1,payload.length);response[response.length-1]=0x10;read=0;
  }
  public int read(byte[] data,int offset,int count,int timeout){int n=Math.min(3,Math.min(count,response.length-read));System.arraycopy(response,read,data,offset,n);read+=n;return n;}
 }
 @Test public void writes128BytePagesAndVerifiesPartialReads()throws Exception{Bootloader b=new Bootloader();byte[] bytes=new byte[150];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)i;assertEquals(150,new Stk500Uploader(b).upload(new IntelHex.Image(bytes,150),32256,128,new byte[]{30,(byte)149,15},null));assertArrayEquals(bytes,Arrays.copyOf(b.flash,150));assertEquals(2,b.pages);assertEquals((byte)255,b.flash[255]);}
 @Test public void rejectsWrongMcuAndReadbackMismatch()throws Exception{for(boolean signature:new boolean[]{true,false}){Bootloader b=new Bootloader();b.wrongSignature=signature;b.corrupt=!signature;try{new Stk500Uploader(b).upload(new IntelHex.Image(new byte[]{1,2},2),32256,128,new byte[]{30,(byte)149,15},null);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains(signature?"Підпис":"flash"));}}}
 @Test public void parsesAddressRecordsAndRejectsDamagedHex()throws Exception{assertArrayEquals(new byte[]{1,2,3,4},IntelHex.parse(":0400000001020304F2\n:00000001FF\n",32768).bytes);for(String text:new String[]{":0400000001020304F3\n:00000001FF",":020000040001F9\n:0100000001FE\n:00000001FF",":0400000001020304F2"})try{IntelHex.parse(text,32768);fail();}catch(IOException expected){}}
}
