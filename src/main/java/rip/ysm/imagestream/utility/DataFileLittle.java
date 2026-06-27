package rip.ysm.imagestream.utility;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class DataFileLittle extends DataFileReader {
   public DataFileLittle(File f) throws IOException { super(f); }
   public DataFileLittle(RandomAccessFile raf) throws IOException { super(raf); }

   @Override
   public int getU16() throws IOException { return this.getU8() | this.getU8() << 8; }

   @Override
   public int getU24() throws IOException { return this.getU8() | this.getU8() << 8 | this.getU8() << 16; }

   @Override
   public int getU32() throws IOException { return this.getU8() | this.getU8() << 8 | this.getU8() << 16 | this.getU8() << 24; }

   public String getFOURCC() throws IOException {
      byte[] bb = new byte[4];
      this.read(bb);
      return new String(bb);
   }
}
