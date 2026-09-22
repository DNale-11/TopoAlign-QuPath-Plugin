package io.github.dnale11.topoalign;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferFloat;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.regions.RegionRequest;

/** Export a single native intensity channel at Z=0, T=0, without display LUTs. */
final class ImageExport {
    private ImageExport() { }

    static void export(Path input, Path output, double downsample, int channel) throws Exception {
        try (var server = ImageServerProvider.buildServer(input.toString(), BufferedImage.class)) {
            if (channel < 1 || channel > server.nChannels())
                throw new IOException("Channel " + channel + " is unavailable in " + input.getFileName()
                        + " (" + server.nChannels() + " channels)");
            double pixels = Math.ceil(server.getWidth() / downsample) * Math.ceil(server.getHeight() / downsample);
            if (pixels > 16_000_000 || pixels * server.nChannels() > 64_000_000)
                throw new IOException("Export too large. Increase downsample: maximum 16 million pixels / 64 million channel samples.");
            var request = RegionRequest.createInstance(server.getPath(), downsample, 0, 0,
                    server.getWidth(), server.getHeight(), 0, 0);
            var source = server.readRegion(request);
            writeChannel(source, output, channel);
        }
    }

    static void writeChannel(BufferedImage source, Path output, int channel) throws IOException {
        int w = source.getWidth(), h = source.getHeight();
        var samples = source.getRaster().getSamples(0, 0, w, h, channel - 1, (float[]) null);
        var buffer = new DataBufferFloat(samples, samples.length);
        var model = new java.awt.image.ComponentSampleModel(DataBuffer.TYPE_FLOAT, w, h, 1, w, new int[]{0});
        var raster = Raster.createWritableRaster(model, buffer, null);
        var colors = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), false, false,
                Transparency.OPAQUE, DataBuffer.TYPE_FLOAT);
        var image = new BufferedImage(colors, raster, false, null);
        if (!ImageIO.write(image, "TIFF", output.toFile())) throw new IOException("No TIFF writer is available");
    }
}
