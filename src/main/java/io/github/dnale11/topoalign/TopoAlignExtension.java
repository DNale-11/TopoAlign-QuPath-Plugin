package io.github.dnale11.topoalign;

import javafx.scene.control.MenuItem;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;

/** QuPath service entry point. */
public final class TopoAlignExtension implements QuPathExtension, GitHubProject {
    private boolean installed;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed) return;
        installed = true;
        var command = new RegistrationCommand(qupath);
        var item = new MenuItem("Register images...");
        item.setOnAction(event -> command.show());
        qupath.getMenu("Extensions>TopoAlign", true).getItems().add(item);
    }

    @Override public String getName() { return "TopoAlign"; }
    @Override public String getDescription() { return "Topology-guided cell image registration using the TopoAlign Python core."; }
    @Override public Version getQuPathVersion() { return Version.parse("0.7.0"); }
    @Override public GitHubRepo getRepository() {
        return GitHubRepo.create("TopoAlign QuPath Plugin", "DNale-11", "TopoAlign-QuPath-Plugin");
    }
}
