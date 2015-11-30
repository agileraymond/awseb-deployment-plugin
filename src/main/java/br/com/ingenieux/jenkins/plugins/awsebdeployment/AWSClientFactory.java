package br.com.ingenieux.jenkins.plugins.awsebdeployment;

import com.amazonaws.AmazonWebServiceClient;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSCredentialsProviderChain;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.internal.StaticCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3;
import com.cloudbees.jenkins.plugins.awscredentials.AmazonWebServicesCredentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import org.apache.commons.lang.reflect.ConstructorUtils;
import org.apache.commons.lang.reflect.FieldUtils;

import javax.security.auth.login.CredentialNotFoundException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.Collections;
import java.util.List;

import static org.apache.commons.lang.StringUtils.defaultString;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class AWSClientFactory implements Constants {

    private AWSCredentialsProvider creds;

    private ClientConfiguration clientConfiguration;

    private String region;

    private AWSClientFactory(AWSCredentialsProvider creds, ClientConfiguration clientConfiguration,
                            String region) {
        this.creds = creds;
        this.clientConfiguration = clientConfiguration;
        this.region = region.toLowerCase();
    }

    @SuppressWarnings("unchecked")
    public <T> T getService(Class<T> serviceClazz)
            throws NoSuchMethodException, IllegalAccessException,
            InvocationTargetException, InstantiationException {

        Class<?> paramTypes[] = new Class<?>[]{AWSCredentialsProvider.class, ClientConfiguration.class};

        ClientConfiguration newClientConfiguration = new ClientConfiguration(this.clientConfiguration);

        if (AmazonS3.class.isAssignableFrom(serviceClazz)) {
            newClientConfiguration = newClientConfiguration.withSignerOverride("AWSS3V4SignerType");
        } else {
            newClientConfiguration = newClientConfiguration.withSignerOverride(null);
        }

        Object params[] = new Object[]{creds, newClientConfiguration};

        T resultObj = (T) ConstructorUtils.invokeConstructor(serviceClazz, params, paramTypes);

        if (DEFAULT_REGION.equals(defaultString(region, DEFAULT_REGION))) {
            return resultObj;
        } else {
            for (ServiceEndpointFormatter formatter : ServiceEndpointFormatter.values()) {
                if (formatter.matches(resultObj)) {
                    ((AmazonWebServiceClient) resultObj).setEndpoint(getEndpointFor(formatter));
                    break;
                }
            }
        }

        return resultObj;
    }

    protected String getEndpointFor(ServiceEndpointFormatter formatter) {
        return String.format(formatter.serviceMask, region);
    }

    public static AWSClientFactory getClientFactory(String credentialsId, String awsRegion) throws CredentialNotFoundException {
        AWSCredentialsProvider credentials = new DefaultAWSCredentialsProviderChain();

        if (isNotBlank(credentialsId)) {
            List<AmazonWebServicesCredentials> credentialList =
                    CredentialsProvider.lookupCredentials(
                            AmazonWebServicesCredentials.class, Jenkins.getInstance(), ACL.SYSTEM,
                            Collections.<DomainRequirement>emptyList());

            AmazonWebServicesCredentials cred =
                    CredentialsMatchers.firstOrNull(credentialList,
                            CredentialsMatchers.allOf(CredentialsMatchers.withId(credentialsId)));

            if (cred == null)
                throw new CredentialNotFoundException(credentialsId);

            credentials = new AWSCredentialsProviderChain(new StaticCredentialsProvider(new BasicAWSCredentials(cred.getCredentials().getAWSAccessKeyId(), cred.getCredentials().getAWSSecretKey())));
        }

        ClientConfiguration clientConfig = new ClientConfiguration();

        clientConfig.setUserAgent("ingenieux CloudButler/" + Utils.getVersion());

        return new AWSClientFactory(credentials, clientConfig, awsRegion);
    }

    public <T extends AmazonWebServiceClient> String getEndpointFor(T client) {
        try {
            URI endpointUri = (URI) FieldUtils.readField(client, "endpoint", true);

            return endpointUri.toASCIIString();
        } catch (Exception e) {
            return null;
        }
    }
}