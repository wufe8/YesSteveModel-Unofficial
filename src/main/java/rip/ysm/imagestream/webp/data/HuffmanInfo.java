package rip.ysm.imagestream.webp.data;

import java.awt.image.Raster;

class HuffmanInfo {
   public final Raster huffmanMetaCodes;
   public final int metaCodeBits;
   public final HuffmanGroup[] huffmanGroups;

   public HuffmanInfo(Raster huffmanMetaCodes, int metaCodeBits, HuffmanGroup[] huffmanGroups) {
      this.huffmanMetaCodes = huffmanMetaCodes;
      this.metaCodeBits = metaCodeBits;
      this.huffmanGroups = huffmanGroups;
   }
}
