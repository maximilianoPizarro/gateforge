package io.gateforge.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class KuadrantCtlService {

    private static final Logger LOG = Logger.getLogger(KuadrantCtlService.class);

    @ConfigProperty(name = "gateforge.kuadrantctl.path", defaultValue = "/usr/local/bin/kuadrantctl")
    String kuadrantctlPath;

    public String generateHttpRoute(String oasContent) {
        return runWithStdin("generate", "gatewayapi", "httproute", "--oas", "-", oasContent);
    }

    public String generateAuthPolicy(String oasContent) {
        return runWithStdin("generate", "kuadrant", "authpolicy", "--oas", "-", oasContent);
    }

    public String generateRateLimitPolicy(String oasContent) {
        return runWithStdin("generate", "kuadrant", "ratelimitpolicy", "--oas", "-", oasContent);
    }

    public String topology(String namespace) {
        return run("topology", "-n", namespace);
    }

    public String version() {
        return run("version");
    }

    private String run(String... args) {
        try {
            String[] command = new String[args.length + 1];
            command[0] = kuadrantctlPath;
            System.arraycopy(args, 0, command, 1, args.length);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "ERROR: kuadrantctl timed out";
            }
            return output;
        } catch (Exception e) {
            LOG.error("kuadrantctl execution failed", e);
            return "ERROR: " + e.getMessage();
        }
    }

    private String runWithStdin(String subCmd1, String subCmd2, String subCmd3, String flag, String stdinMarker, String stdinContent) {
        try {
            ProcessBuilder pb = new ProcessBuilder(kuadrantctlPath, subCmd1, subCmd2, subCmd3, flag, stdinMarker);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (OutputStream os = process.getOutputStream()) {
                os.write(stdinContent.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "ERROR: kuadrantctl timed out";
            }
            return output;
        } catch (Exception e) {
            LOG.error("kuadrantctl execution failed", e);
            return "ERROR: " + e.getMessage();
        }
    }
}
