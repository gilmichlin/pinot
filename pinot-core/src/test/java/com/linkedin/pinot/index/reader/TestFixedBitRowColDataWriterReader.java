package com.linkedin.pinot.index.reader;

import java.io.File;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.linkedin.pinot.core.index.reader.impl.FixedBitWidthRowColDataFileReader;
import com.linkedin.pinot.core.index.writer.impl.FixedBitWidthRowColDataFileWriter;

public class TestFixedBitRowColDataWriterReader {
  @Test
  public void testSiingleColUnsigned() throws Exception {
    int maxBits = 1;
    while (maxBits < 32) {
      System.out.println("START test maxBits:" + maxBits);
      final String fileName = getClass().getName() + "_single_col_fixed_bit_"
          + maxBits + ".dat";
      final File file = new File(fileName);
      file.delete();
      final int rows = 100;
      final int cols = 1;
      final int[] columnSizesInBits = new int[] { maxBits };
      final FixedBitWidthRowColDataFileWriter writer = new FixedBitWidthRowColDataFileWriter(
          file, rows, cols, columnSizesInBits);
      final int[] data = new int[rows];
      final Random r = new Random();
      writer.open();
      final int maxValue = (int) Math.pow(2, maxBits);
      for (int i = 0; i < rows; i++) {
        data[i] = r.nextInt(maxValue);
        writer.setInt(i, 0, data[i]);
      }
      writer.saveAndClose();

      FixedBitWidthRowColDataFileReader reader = FixedBitWidthRowColDataFileReader
          .forHeap(file, rows, cols, columnSizesInBits);
      for (int i = 0; i < rows; i++) {
        Assert.assertEquals(reader.getInt(i, 0), data[i]);
      }
      maxBits = maxBits + 1;
      file.delete();
    }
  }

  @Test
  public void testSingleColUnsigned() throws Exception {
    int maxBits = 1;
    while (maxBits < 32) {
      System.out.println("START test maxBits:" + maxBits);
      final String fileName = getClass().getName() + "_single_col_fixed_bit_"
          + maxBits + ".dat";
      final File file = new File(fileName);
      file.delete();
      final int rows = 100;
      final int cols = 1;
      final int[] columnSizesInBits = new int[] { maxBits };
      final FixedBitWidthRowColDataFileWriter writer = new FixedBitWidthRowColDataFileWriter(
          file, rows, cols, columnSizesInBits, new boolean[] { true });
      final int[] data = new int[rows];
      final Random r = new Random();
      writer.open();
      final int maxValue = (int) Math.pow(2, maxBits);
      for (int i = 0; i < rows; i++) {
        data[i] = r.nextInt(maxValue) * ((Math.random() > .5) ? 1 : -1);
        writer.setInt(i, 0, data[i]);
      }
      writer.saveAndClose();

      FixedBitWidthRowColDataFileReader reader = FixedBitWidthRowColDataFileReader
          .forHeap(file, rows, cols, columnSizesInBits, new boolean[] { true });
      for (int i = 0; i < rows; i++) {
        Assert.assertEquals(reader.getInt(i, 0), data[i]);
      }
      maxBits = maxBits + 1;
      file.delete();
    }
  }
}