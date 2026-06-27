package rip.ysm.imagestream.webp.data;

class HuffmanGroup {
   public final HuffmanTable mainCode;
   public final HuffmanTable redCode;
   public final HuffmanTable blueCode;
   public final HuffmanTable alphaCode;
   public final HuffmanTable distanceCode;

   public HuffmanGroup(WBit bReader, int colorCacheBits) {
      this.mainCode = new HuffmanTable(bReader, 280 + (colorCacheBits > 0 ? 1 << colorCacheBits : 0));
      this.redCode = new HuffmanTable(bReader, 256);
      this.blueCode = new HuffmanTable(bReader, 256);
      this.alphaCode = new HuffmanTable(bReader, 256);
      this.distanceCode = new HuffmanTable(bReader, 40);
   }
}
