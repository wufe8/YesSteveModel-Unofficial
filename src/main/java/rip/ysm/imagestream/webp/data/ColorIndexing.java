package rip.ysm.imagestream.webp.data;

import java.awt.image.WritableRaster;

final class ColorIndexing implements WTransform {
   private final byte[] colorTable;
   private final byte bits;

   public ColorIndexing(byte[] colorTable, byte bits) {
      this.colorTable = colorTable;
      this.bits = bits;
   }

   @Override
   public void perform(WritableRaster raster) {
      int width = raster.getWidth();
      int height = raster.getHeight();
      byte[] rgba = new byte[4];

      for (int y = 0; y < height; y++) {
         for (int x = width - 1; x >= 0; x--) {
            int componentSize = 8 >> this.bits;
            int packed = 1 << this.bits;
            int xC = x / packed;
            int componentOffset = componentSize * (x % packed);
            int sample = raster.getSample(xC, y, 1);
            int index = sample >> componentOffset & (1 << componentSize) - 1;
            System.arraycopy(this.colorTable, index * 4, rgba, 0, 4);
            raster.setDataElements(x, y, rgba);
         }
      }
   }
}
