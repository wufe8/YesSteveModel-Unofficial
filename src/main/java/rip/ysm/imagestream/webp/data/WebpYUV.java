package rip.ysm.imagestream.webp.data;

final class WebpYUV {
   private static final int[] _011 = new int[]{0, 1, 1};
   private static final int[] _012 = new int[]{0, 1, 2};
   static final WebpYUV YUV420 = new WebpYUV();
   final int nComp = 3;
   final int[] compPlane = _012;
   final int[] compWidth = _011;
   final int[] compHeight = _011;

   private WebpYUV() {
   }
}
