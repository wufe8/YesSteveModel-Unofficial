package rip.ysm.imagestream.utility;

public class DataByteLittle extends DataByteReader {
   public DataByteLittle(byte[] data) { super(data); }

   @Override
   public int getU16() {
      return this.data[this.p++] & 0xFF | (this.data[this.p++] & 0xFF) << 8;
   }

   @Override
   public int getU24() {
      return this.data[this.p++] & 0xFF | (this.data[this.p++] & 0xFF) << 8 | (this.data[this.p++] & 0xFF) << 16;
   }

   @Override
   public int getU32() {
      return this.data[this.p++] & 0xFF | (this.data[this.p++] & 0xFF) << 8 | (this.data[this.p++] & 0xFF) << 16 | (this.data[this.p++] & 0xFF) << 24;
   }

   @Override
   public void close() {}

   public String getFOURCC() {
      byte[] bb = new byte[4];
      this.read(bb);
      return new String(bb);
   }
}
