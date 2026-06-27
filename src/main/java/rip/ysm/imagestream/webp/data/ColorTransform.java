package rip.ysm.imagestream.webp.data;

import java.awt.image.Raster;
import java.awt.image.WritableRaster;

final class ColorTransform implements WTransform {
   private final Raster data;
   private final byte bits;

   public ColorTransform(Raster raster, byte bits) {
      this.data = raster;
      this.bits = bits;
   }

   @Override
   public void perform(WritableRaster raster) {
      int width = raster.getWidth();
      int height = raster.getHeight();
      byte[] rgba = new byte[4];

      for (int y = 0; y < height; y++) {
         for (int x = 0; x < width; x++) {
            this.data.getDataElements(x >> this.bits, y >> this.bits, rgba);
            ColorTransformElement trans = new ColorTransformElement(rgba);
            raster.getDataElements(x, y, rgba);
            trans.inverseTransform(rgba);
            raster.setDataElements(x, y, rgba);
         }
      }
   }

   private static final class ColorTransformElement {
      final int green_to_red;
      final int green_to_blue;
      final int red_to_blue;

      ColorTransformElement(byte[] rgba) {
         this.green_to_red = rgba[2];
         this.green_to_blue = rgba[1];
         this.red_to_blue = rgba[0];
      }

      private void inverseTransform(byte[] rgb) {
         int tmp_red = rgb[0];
         int tmp_blue = rgb[2];
         tmp_red += delta((byte)this.green_to_red, rgb[1]);
         tmp_blue += delta((byte)this.green_to_blue, rgb[1]);
         tmp_blue += delta((byte)this.red_to_blue, (byte)tmp_red);
         rgb[0] = (byte)(tmp_red & 0xFF);
         rgb[2] = (byte)(tmp_blue & 0xFF);
      }

      private static byte delta(byte t, byte c) {
         return (byte)(t * c >> 5);
      }
   }
}
