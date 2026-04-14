package ai.pipestream.grpc.gatherer.gradle.internal;

import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

public final class GitCredentials {

    private GitCredentials() {
    }

    public static CredentialsProvider from(String token, String username, String password) {
        String trimmedToken = token == null ? "" : token.trim();
        if (!trimmedToken.isEmpty()) {
            return new UsernamePasswordCredentialsProvider("x-access-token", trimmedToken);
        }

        String trimmedUsername = username == null ? "" : username.trim();
        if (trimmedUsername.isEmpty()) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider(trimmedUsername, password == null ? "" : password);
    }
}
