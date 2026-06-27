package rip.ysm.imagestream.webp.data;

public final class Vp8LBit {
   private static final int[] KBITMASK = new int[]{
      0, 1, 3, 7, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383, 32767, 65535, 131071, 262143, 524287, 1048575, 2097151, 4194303, 8388607, 16777215
   };
   private static final int VP8L_MAX_NUM_BIT_READ = 24;
   private static final int VP8L_LBITS = 64;
   private final byte[] buf;
   private final int len;
   private long val;
   private int pos;
   int bit_pos;
   private int eos;

   public Vp8LBit(byte[] data) {
      this.buf = data;
      this.len = data.length;
      this.pos = 0;
      this.eos = 0;
      this.initBitReader(8);
   }

   private void initBitReader(int length) {
      long value = 0L;

      for (int i = 0; i < length; i++) {
         value |= (this.buf[i] & 255) * 1L << 8 * i;
      }

      this.val = value;
      this.pos = length;
   }

   public int readBits(int n_bits) {
      if (this.eos == 0 && n_bits <= 24) {
         int valx = this.prefetchBits() & KBITMASK[n_bits];
         this.bit_pos += n_bits;
         this.shiftBytes();
         return valx;
      } else {
         this.setEndOfStream();
         return 0;
      }
   }

   void setEndOfStream() {
      this.eos = 1;
      this.bit_pos = 0;
   }

   void shiftBytes() {
      while (this.bit_pos >= 8 && this.pos < this.len) {
         this.val >>>= 8;
         this.val = this.val | (this.buf[this.pos] & 255) * 1L << 56;
         this.pos++;
         this.bit_pos -= 8;
      }

      if (this.isEndOfStream()) {
         this.setEndOfStream();
      }
   }

   int prefetchBits() {
      return (int)(this.val >>> (this.bit_pos & 63));
   }

   boolean isEndOfStream() {
      return this.eos != 0 || this.pos == this.len && this.bit_pos > 64;
   }

   void setBitPos(int p) {
      this.bit_pos = p;
   }
}
