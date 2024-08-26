package org.jenkinsci.plugins.oic.e2e;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.GroupResource;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class OicE2ETest {

    private static final String REALM = "test-realm";

    @Rule
    public KeycloakContainer keycloak = new KeycloakContainer().useTls();

    @Before
    public void setUpKeyloak() {
        try (Keycloak keycloakAdmin = keycloak.getKeycloakAdminClient()) {
            RealmRepresentation testRealm = new RealmRepresentation();
            testRealm.setRealm(REALM);
            testRealm.setId(REALM);
            testRealm.setDisplayName(REALM);

            keycloakAdmin.realms().create(testRealm);

            // Add groups and subgroups
            GroupRepresentation employees = new GroupRepresentation();
            employees.setName("employees");
            keycloakAdmin.realm(REALM).groups().add(employees);

            String groupId = keycloakAdmin.realm(REALM).groups().groups().get(0).getId();

            GroupRepresentation devs = new GroupRepresentation();
            devs.setName("devs");
            GroupResource group = keycloakAdmin.realm(REALM).groups().group(groupId);
            group.subGroup(devs);

            GroupRepresentation sales = new GroupRepresentation();
            sales.setName("sales");
            group = keycloakAdmin.realm(REALM).groups().group(groupId);
            group.subGroup(sales);

            UserRepresentation bob = new UserRepresentation();
            bob.setEmail("bob@acme.org");
            bob.setUsername("bob");
            bob.setGroups(Arrays.asList("/employees", "/employees/devs"));
            bob.setEmailVerified(true);
            bob.setEnabled(true);
            keycloakAdmin.realm(REALM).users().create(bob);

            UserRepresentation john = new UserRepresentation();
            john.setEmail("john@acme.org");
            john.setUsername("john");
            john.setGroups(Arrays.asList("/employees", "/employees/sales"));
            john.setEmailVerified(true);
            john.setEnabled(true);
            keycloakAdmin.realm(REALM).users().create(john);

            // Assert that the realm is properly created
            assertThat("group is created", keycloakAdmin.realm(REALM).groups().groups().get(0).getName(), is("employees"));
            GroupResource g = keycloakAdmin.realm(REALM).groups().group(groupId);
            assertThat("subgroups are created",
                       g.getSubGroups(0, 2, true).stream().map(GroupRepresentation::getName).collect(Collectors.toList()),
                       containsInAnyOrder("devs", "sales"));
            assertThat("users are created", keycloakAdmin.realm(REALM).users().list().stream().map(UserRepresentation::getUsername).collect(Collectors.toList()),
                       containsInAnyOrder("bob", "john"));
            String bobId = keycloakAdmin.realm(REALM).users().searchByUsername("bob", true).get(0).getId();
            assertThat("User bob with the correct groups",
                       keycloakAdmin.realm(REALM).users().get(bobId).groups().stream().map(GroupRepresentation::getPath).collect(Collectors.toList()),
                       containsInAnyOrder("/employees", "/employees/devs"));
            String johnId = keycloakAdmin.realm(REALM).users().searchByUsername("john", true).get(0).getId();
            assertThat("User john with the correct groups",
                       keycloakAdmin.realm(REALM).users().get(johnId).groups().stream().map(GroupRepresentation::getPath).collect(Collectors.toList()),
                       containsInAnyOrder("/employees", "/employees/sales"));
        }
    }

    @Test
    public void test() {
        System.out.println("FRAN!!!!");
        System.out.println("FRAN: " + keycloak.getAuthServerUrl());
/*
        Keycloak keycloakAdmin = keycloak.getKeycloakAdminClient();
        keycloakAdmin.realm("testrealm").groups().
*/
/*
        RealmResource realm = keycloakClient.realm(KeycloakContainer.MASTER_REALM);
        ClientRepresentation client = realm.clients().findByClientId(KeycloakContainer.ADMIN_CLI_CLIENT).get(0);
*/    }

}
