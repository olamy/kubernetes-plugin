package org.csanchez.jenkins.plugins.kubernetes;

import java.io.IOException;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy;
import org.jvnet.localizer.Localizable;
import org.jvnet.localizer.ResourceBundleHolder;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.Cloud;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import io.fabric8.kubernetes.api.model.DoneablePod;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ClientPodResource;
import jenkins.model.Jenkins;

/**
 * @author Carlos Sanchez carlos@apache.org
 */
public class KubernetesSlave extends AbstractCloudSlave {

    private static final Logger LOGGER = Logger.getLogger(KubernetesSlave.class.getName());

    private static final long serialVersionUID = -8642936855413034232L;

    /**
     * The resource bundle reference
     */
    private final static ResourceBundleHolder HOLDER = ResourceBundleHolder.get(Messages.class);

    private final String cloudName;

    public KubernetesSlave(PodTemplate template, String nodeDescription, KubernetesCloud cloud, String labelStr)
            throws Descriptor.FormException, IOException {

        this(template, nodeDescription, cloud, labelStr, new OnceRetentionStrategy(cloud.getRetentionTimeout()));
    }

    @Deprecated
    public KubernetesSlave(PodTemplate template, String nodeDescription, KubernetesCloud cloud, Label label)
            throws Descriptor.FormException, IOException {
        this(template, nodeDescription, cloud, label.toString(), new OnceRetentionStrategy(cloud.getRetentionTimeout())) ;
    }

    @Deprecated
    public KubernetesSlave(PodTemplate template, String nodeDescription, KubernetesCloud cloud, String labelStr,
                           RetentionStrategy rs)
            throws Descriptor.FormException, IOException {
        this(template, nodeDescription, cloud.name, labelStr, rs);
    }

    @DataBoundConstructor
    public KubernetesSlave(PodTemplate template, String nodeDescription, String cloudName, String labelStr,
                           RetentionStrategy rs)
            throws Descriptor.FormException, IOException {

        super(getSlaveName(template),
                nodeDescription,
                template.getRemoteFs(),
                1,
                template.getMode(),
                labelStr == null ? null : labelStr,
                new JNLPLauncher(),
                rs,
                template.getNodeProperties());

        // this.pod = pod;
        this.cloudName = cloudName;
    }

    static String getSlaveName(PodTemplate template) {
        String hex = Long.toHexString(System.nanoTime());
        String name = template.getName();
        if (StringUtils.isEmpty(name)) {
            return hex;
        }
        // no spaces
        name = template.getName().replace(" ", "-").toLowerCase();
        // keep it under 256 chars
        name = name.substring(0, Math.min(name.length(), 256 - hex.length()));
        return String.format("%s-%s", name, hex);
    }

    @Override
    public KubernetesComputer createComputer() {
        return new KubernetesComputer(this);
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        LOGGER.log(Level.INFO, "Terminating Kubernetes instance for slave {0}", name);

        Computer computer = toComputer();
        if (computer == null) {
            LOGGER.log(Level.SEVERE, "Computer for slave is null: {0}", name);
            return;
        }

        if (cloudName == null) {
            LOGGER.log(Level.SEVERE, "Cloud name is not set for slave, can't terminate: {0}", name);
        }

        try {
            Cloud cloud = Jenkins.getInstance().getCloud(cloudName);
            if (!(cloud instanceof KubernetesCloud)) {
                LOGGER.log(Level.SEVERE, "Slave cloud is not a KubernetesCloud, something is very wrong: {0}", name);
            }
            KubernetesClient client = ((KubernetesCloud) cloud).connect();
            ClientPodResource<Pod, DoneablePod> pods = client.pods().withName(name);
            pods.delete();
            LOGGER.log(Level.INFO, "Terminated Kubernetes instance for slave {0}", name);
            computer.disconnect(OfflineCause.create(new Localizable(HOLDER, "offline")));
            LOGGER.log(Level.INFO, "Disconnected computer {0}", name);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to terminate pod for slave " + name, e);
        }
    }

    @Override
    public String toString() {
        return String.format("KubernetesSlave name: %s", name);
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "Kubernetes Slave";
        };

        @Override
        public boolean isInstantiable() {
            return false;
        }

    }
}
