package io.github.dnale11.topoalign;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageExportTest {
    @TempDir Path temp;

    @Test void exportsSelectedChannelWithoutDisplayRescaling() throws Exception {
        var source = new BufferedImage(17, 13, BufferedImage.TYPE_INT_RGB);
        source.setRGB(4, 6, (15 << 16) | (147 << 8) | 92);
        Path out = temp.resolve("green.tif");
        ImageExport.writeChannel(source, out, 2);
        var read = ImageIO.read(out.toFile());
        assertEquals(147f, read.getRaster().getSampleFloat(4, 6, 0));
        assertEquals(17, read.getWidth()); assertEquals(13, read.getHeight());
    }
}
