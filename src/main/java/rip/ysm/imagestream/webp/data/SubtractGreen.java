package rip.ysm.imagestream.webp.data;

import java.awt.image.WritableRaster;

final class SubtractGreen implements WTransform {
   private static void addGreenToBlueAndRed(byte[] rgb) {
      rgb[0] = (byte)(rgb[0] + rgb[1] & 0xFF);
      rgb[2] = (byte)(rgb[2] + rgb[1] & 0xFF);
   }

   @Override
   public void perform(WritableRaster raster) {
      int width = raster.getWidth();
      int height = raster.getHeight();
      byte[] rgba = new byte[4];

      for (int y = 0; y < height; y++) {
         for (int x = 0; x < width; x++) {
            raster.getDataElements(x, y, rgba);
            addGreenToBlueAndRed(rgba);
            raster.setDataElements(x, y, rgba);
         }
      }
   }
}
