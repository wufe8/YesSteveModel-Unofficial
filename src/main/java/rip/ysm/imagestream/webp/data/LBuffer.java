package rip.ysm.imagestream.webp.data;

class LBuffer {
   final byte[] data;
   private int p;

   LBuffer(byte[] d) {
      this.data = d;
   }

   int get(int x) {
      return this.data[x] & 0xFF;
   }

   void put(byte b) {
      this.data[this.p++] = b;
   }

   void put(byte[] b) {
      System.arraycopy(b, 0, this.data, this.p, b.length);
      this.p += b.length;
   }

   void put(int x, byte b) {
      this.data[x] = b;
   }

   void position(int i) {
      this.p = i;
   }

   int position() {
      return this.p;
   }

   void putShort(short n) {
      int v = n & '\uffff';
      this.data[this.p++] = (byte)(v & 0xFF);
      this.data[this.p++] = (byte)(v >> 8 & 0xFF);
   }
}
