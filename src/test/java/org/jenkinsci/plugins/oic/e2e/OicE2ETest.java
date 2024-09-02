package org.jenkinsci.plugins.oic.e2e;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.stream.Collectors;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.htmlunit.FormEncodingType;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.oic.OicSecurityRealm;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RealJenkinsRule;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.GroupResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

public class OicE2ETest {

    private static final String REALM = "test-realm";
    private static final String CLIENT = "jenkins";

    @Rule
    public KeycloakContainer keycloak = new KeycloakContainer()/*.useTls()*/;
    @Rule
    public RealJenkinsRule jenkins = new RealJenkinsRule();

    private String keycloakUrl;

    @Before
    public void setUpKeycloak() throws Throwable {
        keycloakUrl = keycloak.getAuthServerUrl();
        configureOIDCProvider();
        jenkins.then(new ConfigureRealm(keycloakUrl));
    }

    private void configureOIDCProvider() {
        try (Keycloak keycloakAdmin = keycloak.getKeycloakAdminClient()) {
            RealmRepresentation testRealm = new RealmRepresentation();
            testRealm.setRealm(REALM);
            testRealm.setId(REALM);
            testRealm.setDisplayName(REALM);

            keycloakAdmin.realms().create(testRealm);

            // Add groups and subgroups
            GroupRepresentation employees = new GroupRepresentation();
            employees.setName("employees");

            final RealmResource theRealm = keycloakAdmin.realm(REALM);
            theRealm.groups().add(employees);

            String groupId = theRealm.groups().groups().get(0).getId();

            GroupRepresentation devs = new GroupRepresentation();
            devs.setName("devs");
            GroupResource group = theRealm.groups().group(groupId);
            group.subGroup(devs);

            GroupRepresentation sales = new GroupRepresentation();
            sales.setName("sales");
            group = theRealm.groups().group(groupId);
            group.subGroup(sales);

            // Users
            UserRepresentation bob = new UserRepresentation();
            bob.setEmail("bob@acme.org");
            bob.setUsername("bob");
            bob.setGroups(Arrays.asList("/employees", "/employees/devs"));
            bob.setEmailVerified(true);
            bob.setEnabled(true);
            theRealm.users().create(bob);

            UserRepresentation john = new UserRepresentation();
            john.setEmail("john@acme.org");
            john.setUsername("john");
            john.setGroups(Arrays.asList("/employees", "/employees/sales"));
            john.setEmailVerified(true);
            john.setEnabled(true);
            theRealm.users().create(john);

            // Client
            ClientRepresentation jenkinsClient = new ClientRepresentation();
            jenkinsClient.setClientId(CLIENT);
            jenkinsClient.setProtocol("openid-connect");
            jenkinsClient.setSecret(CLIENT);
            theRealm.clients().create(jenkinsClient);

            // Assert that the realm is properly created
            assertThat("group is created", theRealm.groups().groups().get(0).getName(), is("employees"));
            GroupResource g = theRealm.groups().group(groupId);
            assertThat("subgroups are created",
                       g.getSubGroups(0, 2, true).stream().map(GroupRepresentation::getName).collect(Collectors.toList()),
                       containsInAnyOrder("devs", "sales"));
            assertThat("users are created", theRealm.users().list().stream().map(UserRepresentation::getUsername).collect(Collectors.toList()),
                       containsInAnyOrder("bob", "john"));
            String bobId = theRealm.users().searchByUsername("bob", true).get(0).getId();
            assertThat("User bob with the correct groups",
                       theRealm.users().get(bobId).groups().stream().map(GroupRepresentation::getPath).collect(Collectors.toList()),
                       containsInAnyOrder("/employees", "/employees/devs"));
            String johnId = theRealm.users().searchByUsername("john", true).get(0).getId();
            assertThat("User john with the correct groups",
                       theRealm.users().get(johnId).groups().stream().map(GroupRepresentation::getPath).collect(Collectors.toList()),
                       containsInAnyOrder("/employees", "/employees/sales"));
            assertThat("client is created",
                       theRealm.clients().findByClientId(CLIENT).get(0).getProtocol(), is("openid-connect"));
        }
    }

    @Test
    public void test() throws Throwable {
        try (Keycloak keycloakAdmin = keycloak.getKeycloakAdminClient()) {
            // anonymous
            jenkins.startJenkins();
            System.out.println("-----------------------------------------------------------------");
            jenkins.runRemotely(new JenkinsWhoAmI(keycloakUrl));
            System.out.println("-----------------------------------------------------------------");
            new KeycloakWhoAmI(keycloakUrl).assertKeycloakWhoAmI(null);
            System.out.println("-----------------------------------------------------------------");
        } finally {
            if (jenkins.isAlive()) {
                jenkins.stopJenkins();
            }
        }
    }

    private static class ConfigureRealm implements RealJenkinsRule.Step {

        private final String rootUrl;

        private ConfigureRealm(String rootUrl) {
            this.rootUrl = rootUrl;
        }

        @Override
        public void run(JenkinsRule r) throws Throwable {
            final String openidConnectUrl = String.format("%s/realms/%s/protocol/openid-connect/", rootUrl, REALM);
            OicSecurityRealm realm = new OicSecurityRealm(CLIENT,
                                                          CLIENT,
                                                          openidConnectUrl + "auth",
                                                          openidConnectUrl + "token",
                                                          openidConnectUrl + "certs",
                                                          "client_secret_post",
                                                          openidConnectUrl + "userinfo",
                                                          openidConnectUrl + "logout",
                                                          /* default scopes for keycloak */ "web-origins acr address phone openid roles profile offline_access microprofile-jwt basic email",
                                                          "auto",
                                                          false,
                                                          true);
            r.jenkins.setSecurityRealm(realm);
        }
    }

    private static class JenkinsWhoAmI implements RealJenkinsRule.Step {

        final private String keycloakUrl;
        final private String user;
        final private boolean needsLogin;

        private JenkinsWhoAmI(String keycloakUrl) {
            this(keycloakUrl, null);
        }

        private JenkinsWhoAmI(String keycloakUrl, String user) {
            this.keycloakUrl = keycloakUrl;
            this.user = StringUtils.trimToEmpty(user).isEmpty() ? "anonymous" : StringUtils.trimToEmpty(user);
            this.needsLogin = !StringUtils.trimToEmpty(user).isEmpty();
        }

        @Override
        public void run(JenkinsRule r) throws Throwable {
            JenkinsRule.WebClient wc = r.createWebClient();

            assertJenkinsWhoAmI(wc);
            assertKeycloakWhoAmI(wc);
        }

        private void assertJenkinsWhoAmI(JenkinsRule.WebClient wc) throws Exception {
            //            wc.login("user");

            Page whoAmIPage = wc.goTo("whoAmI/api/json", "application/json");
            String content = whoAmIPage.getWebResponse().getContentAsString();

            JSONObject response = JSONObject.fromObject(content);
            assertThat("user is the expected one", response.get("name"), is(user));
        }

        private void assertKeycloakWhoAmI(JenkinsRule.WebClient wc) throws Exception {
            //HttpURLConnection conn = (HttpURLConnection) new URL(String.format("%s/realms/%s/account", keycloakUrl, REALM)).openConnection();

            WebResponse response = wc.getPage(String.format("%s/realms/%s/account", keycloakUrl, REALM)).getWebResponse();

            System.out.println("FRAN!! -> " + response.getStatusCode());
            System.out.println("FRAN!! -> " + response.getStatusMessage());
            System.out.println("FRAN!! -> " + response.getContentAsString());
            response.getResponseHeaders().forEach(nameValuePair -> {
                System.out.println("FRAN!! -> " + nameValuePair.getName() + " - " + nameValuePair.getValue());
            });

        }

        private void assertUserInfo(JenkinsRule.WebClient wc, String token) throws Exception {
            final String getUserInfoUrl = String.format("%s/realms/%s/protocol/openid-connect/userinfo", keycloakUrl, REALM);
            WebRequest getUserInfo = new WebRequest(new URL(getUserInfoUrl), HttpMethod.GET);
            getUserInfo.setEncodingType(FormEncodingType.URL_ENCODED);

            NameValuePair clientId = new NameValuePair("client_id", CLIENT);
            NameValuePair clientSecret = new NameValuePair("client_secret", CLIENT);
            getUserInfo.setRequestParameters(Arrays.asList(clientId, clientSecret));

            if (token != null) {
                getUserInfo.setAdditionalHeader("Authorization", "Bearer " + token);
            }

            WebResponse response = wc.getPage(getUserInfo).getWebResponse();

            if (user != null) {
                assertThat("User info successfully retrieved", response.getStatusCode(), is(HttpURLConnection.HTTP_OK));
                JSONObject responseBody = JSONObject.fromObject(response.getContentAsString());
                String username = responseBody.getString("preferred_username");
                assertThat("User info successfully retrieved", username, is(user));
            }
        }

        private String getToken(JenkinsRule.WebClient wc) throws Exception {
            String token = null;
            if (user != null) {
                final String getTokenUrl = String.format("%s/realms/%s/protocol/openid-connect/token", keycloakUrl,
                                                         REALM);
                WebRequest getToken = new WebRequest(new URL(getTokenUrl), HttpMethod.POST);
                getToken.setEncodingType(FormEncodingType.URL_ENCODED);

                NameValuePair clientId = new NameValuePair("client_id", CLIENT);
                NameValuePair clientSecret = new NameValuePair("client_secret", CLIENT);
                NameValuePair grantType = new NameValuePair("grant_type", "password");
                NameValuePair username = new NameValuePair("username", user);
                NameValuePair scope = new NameValuePair("scope", "openid");
                getToken.setRequestParameters(Arrays.asList(clientId, clientSecret, grantType, username, scope));

                WebResponse response = wc.getPage(getToken).getWebResponse();

                assertThat("Token successfully retrieved", response.getStatusCode(), is(HttpURLConnection.HTTP_OK));
                JSONObject responseBody = JSONObject.fromObject(response.getContentAsString());
                token = responseBody.getString("access_token");
                assertNotNull("Token successfully retrieved", token);
            }
            return token;
        }
    }


    private static class KeycloakWhoAmI implements RealJenkinsRule.Step {

        final private String keycloakUrl;
        final private String user;
        final private boolean needsLogin;

        private KeycloakWhoAmI(String keycloakUrl) {
            this(keycloakUrl, null);
        }

        private KeycloakWhoAmI(String keycloakUrl, String user) {
            this.keycloakUrl = keycloakUrl;
            this.user = StringUtils.trimToEmpty(user).isEmpty() ? "anonymous" : StringUtils.trimToEmpty(user);
            this.needsLogin = !StringUtils.trimToEmpty(user).isEmpty();
        }

        @Override
        public void run(JenkinsRule r) throws Throwable {
            JenkinsRule.WebClient wc = r.createWebClient();

            assertJenkinsWhoAmI(wc);
            assertKeycloakWhoAmI(wc);
        }

        private void assertJenkinsWhoAmI(JenkinsRule.WebClient wc) throws Exception {
            //            wc.login("user");

            Page whoAmIPage = wc.goTo("whoAmI/api/json", "application/json");
            String content = whoAmIPage.getWebResponse().getContentAsString();

            JSONObject response = JSONObject.fromObject(content);
            assertThat("user is the expected one", response.get("name"), is(user));
        }

        private void assertKeycloakWhoAmI(JenkinsRule.WebClient wc) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) new URL(String.format("%s/realms/%s/account", keycloakUrl, REALM)).openConnection();

/*            WebResponse response = wc.getPage(String.format("%s/realms/%s/account", keycloakUrl, REALM))
                                     .getWebResponse();*/

            conn.setInstanceFollowRedirects(true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                sb.append(inputLine);
            }
            in.close();
            //print result
            System.out.println("result:::" + sb.toString());


            System.out.println("FRAN!! -> " + conn.getURL());
            System.out.println("FRAN!! -> " + conn.getResponseCode());
            System.out.println("FRAN!! -> " + conn.getResponseMessage());
            conn.getHeaderFields().forEach((s, strings) -> {
                System.out.println("FRAN!! -> " + s + " - " + strings);
            });

        }

        private void assertUserInfo(JenkinsRule.WebClient wc, String token) throws Exception {
            final String getUserInfoUrl = String.format("%s/realms/%s/protocol/openid-connect/userinfo", keycloakUrl, REALM);
            WebRequest getUserInfo = new WebRequest(new URL(getUserInfoUrl), HttpMethod.GET);
            getUserInfo.setEncodingType(FormEncodingType.URL_ENCODED);

            NameValuePair clientId = new NameValuePair("client_id", CLIENT);
            NameValuePair clientSecret = new NameValuePair("client_secret", CLIENT);
            getUserInfo.setRequestParameters(Arrays.asList(clientId, clientSecret));

            if (token != null) {
                getUserInfo.setAdditionalHeader("Authorization", "Bearer " + token);
            }

            WebResponse response = wc.getPage(getUserInfo).getWebResponse();

            if (user != null) {
                assertThat("User info successfully retrieved", response.getStatusCode(), is(HttpURLConnection.HTTP_OK));
                JSONObject responseBody = JSONObject.fromObject(response.getContentAsString());
                String username = responseBody.getString("preferred_username");
                assertThat("User info successfully retrieved", username, is(user));
            }
        }

        private String getToken(JenkinsRule.WebClient wc) throws Exception {
            String token = null;
            if (user != null) {
                final String getTokenUrl = String.format("%s/realms/%s/protocol/openid-connect/token", keycloakUrl,
                                                         REALM);
                WebRequest getToken = new WebRequest(new URL(getTokenUrl), HttpMethod.POST);
                getToken.setEncodingType(FormEncodingType.URL_ENCODED);

                NameValuePair clientId = new NameValuePair("client_id", CLIENT);
                NameValuePair clientSecret = new NameValuePair("client_secret", CLIENT);
                NameValuePair grantType = new NameValuePair("grant_type", "password");
                NameValuePair username = new NameValuePair("username", user);
                NameValuePair scope = new NameValuePair("scope", "openid");
                getToken.setRequestParameters(Arrays.asList(clientId, clientSecret, grantType, username, scope));

                WebResponse response = wc.getPage(getToken).getWebResponse();

                assertThat("Token successfully retrieved", response.getStatusCode(), is(HttpURLConnection.HTTP_OK));
                JSONObject responseBody = JSONObject.fromObject(response.getContentAsString());
                token = responseBody.getString("access_token");
                assertNotNull("Token successfully retrieved", token);
            }
            return token;
        }
    }
}
