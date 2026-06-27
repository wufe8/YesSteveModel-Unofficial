package rip.ysm.imagestream.utility;

public abstract class DataByteReader implements DataReader {
   protected int p;
   protected final byte[] data;

   protected DataByteReader(byte[] data) {
      this.data = data;
      this.p = 0;
   }

   @Override
   public int getU8() {
      return this.data[this.p++] & 0xFF;
   }

   @Override
   public void read(byte[] copyTo) {
      int avail = Math.min(this.data.length - this.p, copyTo.length);
      System.arraycopy(this.data, this.p, copyTo, 0, avail);
      this.p += avail;
   }

   @Override
   public void skip(int n) { this.p += n; }

   @Override
   public void moveTo(int p) { this.p = p; }

   @Override
   public int getLength() { return this.data.length; }

   @Override
   public int getPosition() { return this.p; }
}
