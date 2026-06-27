package rip.ysm.imagestream.webp.data;

import rip.ysm.imagestream.utility.DataByteLittle;

class WBit {
   private final DataByteLittle dataReader;
   private int bitOffset = 64;
   private int wPos = -1;
   private long buffer;

   WBit(DataByteLittle is) {
      this.dataReader = is;
   }

   int readBits(int bits) {
      return this.readBits(bits, false);
   }

   int readForward(int bits) {
      return this.readBits(bits, true);
   }

   private int readBits(int bits, boolean isForward) {
      long v;
      if (bits <= 56) {
         if (this.wPos != this.dataReader.getPosition()) {
            this.reset();
         }

         v = this.buffer >>> this.bitOffset & (1L << bits) - 1L;
         if (!isForward) {
            this.bitOffset += bits;
            if (this.bitOffset >= 8) {
               this.refill();
            }
         }
      } else {
         long lower = this.readBits(56);
         v = (long)this.readBits(bits - 56) << 56 | lower;
      }

      return (int)v;
   }

   private void refill() {
      this.getU64();

      for (; this.bitOffset >= 8; this.bitOffset -= 8) {
         try {
            byte b = (byte)this.dataReader.getU8();
            this.buffer = (long)b << 56 | this.buffer >>> 8;
            this.wPos++;
         } catch (Exception var2) {
            this.dataReader.moveTo(this.wPos);
            return;
         }
      }

      this.dataReader.moveTo(this.wPos);
   }

   private void reset() {
      int inputStreamPosition = this.dataReader.getPosition();

      try {
         this.buffer = this.getU64();
         this.bitOffset = 0;
         this.wPos = inputStreamPosition;
         this.dataReader.moveTo(inputStreamPosition);
      } catch (Exception var3) {
         this.wPos = inputStreamPosition - 8;
         this.bitOffset = 64;
         this.refill();
      }
   }

   int readBit() {
      return this.readBits(1);
   }

   private long getU64() {
      int i1 = this.dataReader.getU32();
      int i2 = this.dataReader.getU32();
      return ((long)i2 << 32) + (i1 & 4294967295L);
   }
}
