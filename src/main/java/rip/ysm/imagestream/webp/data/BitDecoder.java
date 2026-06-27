package rip.ysm.imagestream.webp.data;

import rip.ysm.imagestream.utility.DataByteLittle;

class BitDecoder {
   private int bPos;
   private final DataByteLittle reader;
   private int offset;
   private int range;
   private int value;

   BitDecoder(DataByteLittle reader, int pos) {
      this.reader = reader;
      this.offset = pos;
      this.value = 0;
      this.reader.moveTo(this.offset);
      this.value = this.reader.getU8() << 8;
      this.offset++;
      this.range = 255;
      this.bPos = 0;
   }

   int getBit() {
      return this.getProbBit(128);
   }

   int getProbBit(int prob) {
      int bit = 0;
      int vv = this.value;
      int split = 1 + ((this.range - 1) * prob >> 8);
      int bigsplit = split << 8;
      int rr = split;
      if (vv >= bigsplit) {
         rr = this.range - split;
         vv -= bigsplit;
         bit = 1;
      }

      int shift = LookUp.BITS_NORM[rr];
      rr <<= shift;
      vv <<= shift;
      this.bPos -= shift;
      if (this.bPos <= 0) {
         vv |= this.reader.getU8() << -this.bPos;
         this.offset++;
         this.bPos += 8;
      }

      this.value = vv;
      this.range = rr;
      return bit;
   }

   int getLiteral(int num_bits) {
      int v = 0;

      while (num_bits-- > 0) {
         v = (v << 1) + this.getProbBit(128);
      }

      return v;
   }

   int getTree(int[] t, int[] p) {
      int i = t[this.getProbBit(p[0])];

      while (i > 0) {
         i = t[i + this.getProbBit(p[i >> 1])];
      }

      return -i;
   }

   int skipTree(int[] p) {
      int i = 2;
      i = LookUp.EOB_COEF_TREE[i + this.getProbBit(p[i >> 1])];

      while (i > 0) {
         i = LookUp.EOB_COEF_TREE[i + this.getProbBit(p[i >> 1])];
      }

      return -i;
   }

   void seek() {
      this.reader.moveTo(this.offset);
   }
}
