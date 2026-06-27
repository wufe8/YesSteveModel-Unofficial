package rip.ysm.imagestream.utility;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public abstract class DataFileReader implements DataReader {
   protected int pos;
   protected RandomAccessFile ra;
   protected int len;
   protected byte[] temp;
   protected int tSize;
   protected int ts;
   protected int te;

   protected DataFileReader(File f) throws IOException {
      this.ra = new RandomAccessFile(f, "r");
      this.len = (int)f.length();
      this.tSize = Math.min(8192, this.len);
      this.temp = new byte[this.tSize];
      this.te = this.tSize;
      this.ra.read(this.temp);
   }

   protected DataFileReader(RandomAccessFile raf) throws IOException {
      this.ra = raf;
      this.len = (int)raf.length();
      this.tSize = Math.min(8192, this.len);
      this.temp = new byte[this.tSize];
      this.te = this.tSize;
      this.ra.read(this.temp);
   }

   @Override
   public int getU8() throws IOException {
      if (this.pos >= this.ts && this.pos < this.te) {
         int v = this.temp[this.pos - this.ts] & 255;
         this.pos++;
         return v;
      } else {
         this.ts = this.pos;
         this.te = this.ts + this.tSize;
         this.ra.seek(this.pos);
         int max = Math.min(this.len - this.pos, this.tSize);
         this.ra.read(this.temp, 0, max);
         this.pos++;
         return this.temp[0] & 0xFF;
      }
   }

   @Override
   public void read(byte[] copyTo) throws IOException {
      int ii = Math.min(copyTo.length, this.len - this.pos);
      for (int i = 0; i < ii; i++) {
         copyTo[i] = (byte)this.getU8();
      }
   }

   @Override
   public int getPosition() { return this.pos; }

   @Override
   public void skip(int n) { this.pos += n; }

   @Override
   public void moveTo(int p) { this.pos = p; }

   @Override
   public int getLength() { return this.len; }

   @Override
   public void close() throws IOException { this.ra.close(); }
}
