package rip.ysm.imagestream.webp.data;

class SegmentQuants {
   final SegmentQ[] segQuants = new SegmentQ[4];

   private static int getDQ(BitDecoder bc) {
      int ret = 0;
      if (bc.getBit() > 0) {
         ret = bc.getLiteral(4);
         if (bc.getBit() > 0) {
            ret = -ret;
         }
      }

      return ret;
   }

   SegmentQuants() {
      for (int x = 0; x < 4; x++) {
         this.segQuants[x] = new SegmentQ();
      }
   }

   void parse(BitDecoder bc, boolean hasSegmentation, boolean mb_segement_abs_delta) {
      int index = bc.getLiteral(7);
      int v = getDQ(bc);
      int y1dcdq = v;
      v = getDQ(bc);
      int y2dcdq = v;
      v = getDQ(bc);
      int y2acdq = v;
      v = getDQ(bc);
      int uvdcdq = v;
      v = getDQ(bc);
      int uvacdq = v;

      for (SegmentQ s : this.segQuants) {
         if (!hasSegmentation) {
            s.index = index;
         } else if (!mb_segement_abs_delta) {
            s.index += index;
         }

         s.setY1dc(y1dcdq);
         s.setY2DC(y2dcdq);
         s.setY2ac_delta_q(y2acdq);
         s.setDeltaQUVDC(uvdcdq);
         s.setDeltaQUVAC(uvacdq);
      }
   }
}
