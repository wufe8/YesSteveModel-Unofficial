package rip.ysm.imagestream.webp;

import rip.ysm.imagestream.utility.DataByteLittle;
import rip.ysm.imagestream.utility.DataFileLittle;
import rip.ysm.imagestream.webp.data.Frame;
import rip.ysm.imagestream.webp.data.Vp8LBit;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;

public class WebpDecoder {
   public BufferedImage read(byte[] rawData) throws Exception {
      DataByteLittle reader = new DataByteLittle(rawData);
      String ss = reader.getFOURCC();
      if (!"RIFF".equals(ss)) {
         throw new Exception("Not a valid WEBP file : RIFF header not found");
      } else {
         int fileSize = reader.getU32();
         ss = reader.getFOURCC();
         if (!"WEBP".equals(ss)) {
            throw new Exception("Not a valid WEBP file : WEBP header not found - filesize" + fileSize);
         } else {
            int maxRead = rawData.length - 8;

            while (reader.getPosition() < maxRead) {
               ss = reader.getFOURCC();
               int chunkSize = reader.getU32();
               switch (ss) {
                  case "VP8 ":
                     Frame frm = new Frame(reader);
                     frm.decodeFrame();
                     return frm.getBufferedImage();
                  case "VP8L":
                     byte[] temp = new byte[chunkSize];
                     reader.read(temp);
                     return Frame.decodeLossless(temp);
                  case "ANMF":
                     reader.skip(16);
                     break;
                  default:
                     reader.skip(chunkSize);
                     if (chunkSize % 2 == 1) {
                        reader.skip(1);
                     }
               }
            }

            return null;
         }
      }
   }

   public Rectangle readDimension(File file) throws Exception {
      DataFileLittle readerx = new DataFileLittle(file);
      int maxRead = Math.min(256, (int)file.length());
      byte[] temp = new byte[maxRead];
      readerx.read(temp);
      readerx.close();
      return this.readDimension(temp);
   }

   public Rectangle readDimension(byte[] rawData) throws Exception {
      DataByteLittle reader = new DataByteLittle(rawData);
      String ss = reader.getFOURCC();
      if (!"RIFF".equals(ss)) {
         throw new Exception("Not a valid WEBP file : RIFF header not found");
      } else {
         int fileSize = reader.getU32();
         ss = reader.getFOURCC();
         if (!"WEBP".equals(ss)) {
            throw new Exception("Not a valid WEBP file : WEBP header not found - filesize" + fileSize);
         } else {
            int maxRead = rawData.length - 8;

            while (reader.getPosition() < maxRead) {
               ss = reader.getFOURCC();
               int chunkSize = reader.getU32();
               switch (ss) {
                  case "VP8 ":
                     Frame frm = new Frame(reader);
                     return frm.readDimension();
                  case "VP8L":
                     byte[] temp = new byte[8];
                     reader.read(temp);
                     Vp8LBit br_ = new Vp8LBit(temp);
                     br_.readBits(8);
                     int width = br_.readBits(14) + 1;
                     int height = br_.readBits(14) + 1;
                     return new Rectangle(width, height);
                  case "ANMF":
                     reader.skip(16);
                     break;
                  default:
                     reader.skip(chunkSize);
                     if (chunkSize % 2 == 1) {
                        reader.skip(1);
                     }
               }
            }

            return new Rectangle(0, 0);
         }
      }
   }

   public BufferedImage read(File imageFile) throws Exception {
      BufferedImage var4;
      try (FileInputStream fis = new FileInputStream(imageFile)) {
         byte[] data = new byte[(int)imageFile.length()];
         fis.read(data);
         var4 = this.read(data);
      }

      return var4;
   }
}
