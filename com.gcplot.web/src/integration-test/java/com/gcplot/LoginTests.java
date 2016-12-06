package com.gcplot;

import com.gcplot.commons.ErrorMessages;
import com.gcplot.commons.Utils;
import com.gcplot.messages.ChangePasswordRequest;
import com.gcplot.messages.ChangeUsernameRequest;
import com.gcplot.messages.RegisterRequest;
import com.gcplot.messages.SendNewPassRequest;
import io.vertx.core.json.JsonObject;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class LoginTests extends IntegrationTest {

    @Test
    public void testNaiveErrors() throws Exception {
        get("/user/login?login=abc&password=def", ErrorMessages.WRONG_CREDENTIALS);
        get("/user/confirm?token=tk&salt=sl", ErrorMessages.NOT_AUTHORISED);
        post("/user/register", "{\"username\": \"123\", \"password\": \"pass\"}", ErrorMessages.INTERNAL_ERROR);
    }

    @Test
    public void testFullCycle() throws Exception {
        get("/user/register", ErrorMessages.NOT_FOUND);

        RegisterRequest request = register();
        post("/user/register", request, success());

        JsonObject jo = login(request);
        Assert.assertEquals(jo.getString("username"), request.username);
        Assert.assertEquals(jo.getString("email"), request.email);
        Assert.assertEquals(jo.getBoolean("confirmed"), false);

        get("/user/login?login=" + request.email + "&password=" + request.password, j -> j.containsKey("result"));

        Assert.assertTrue(Utils.waitFor(() -> smtpServer.getReceivedEmails().size() == 1, TimeUnit.SECONDS.toNanos(10)));
        String confirmUrl = smtpServer.getReceivedEmails().get(0).getBody();
        Assert.assertTrue(withRedirect(confirmUrl));

        jo = login(request);
        Assert.assertEquals(jo.getBoolean("confirmed"), true);
    }

    @Test
    public void testRegisterNotUnique() throws Exception {
        RegisterRequest request = register();
        post("/user/register", request, success());

        post("/user/register", request, ErrorMessages.NOT_UNIQUE_FIELDS);
    }

    @Test
    public void testChangePassword() throws Exception {
        RegisterRequest request = register();
        post("/user/register", request, success());

        JsonObject jo = login(request);
        ChangePasswordRequest r = new ChangePasswordRequest("root", null, "123");
        post("/user/change_password?token=" + jo.getString("token"), r, success());
    }

    @Test
    public void testUsernameNotUnique() throws Exception {
        RegisterRequest request = register();
        post("/user/register", request, success());

        request.email = "artem1@gcplot.com";
        request.username = "dmart";
        post("/user/register", request, success());

        JsonObject jo = login(request);

        ChangeUsernameRequest r = new ChangeUsernameRequest("admin");
        post("/user/change_username?token=" +
                jo.getString("token"), r, ErrorMessages.USER_ALREADY_EXISTS);

        r = new ChangeUsernameRequest("admin2");
        post("/user/change_username?token=" +
                jo.getString("token"), r, success());
    }

    @Test
    public void testNewPasswordSend() throws Exception {
        RegisterRequest request = register();
        post("/user/register", request, success());

        Assert.assertTrue(Utils.waitFor(() -> smtpServer.getReceivedEmails().size() == 1, TimeUnit.SECONDS.toNanos(10)));

        JsonObject jo = login(request);

        SendNewPassRequest r = new SendNewPassRequest("artem@gcplot.com");
        post("/user/send/new_password", r, success());
        Assert.assertTrue(Utils.waitFor(() -> smtpServer.getReceivedEmails().size() == 2, TimeUnit.SECONDS.toNanos(10)));
        String newPassUrl = smtpServer.getReceivedEmails().get(1).getBody();

        Assert.assertTrue(newPassUrl.startsWith("http://test.com/?cp=true"));
        Assert.assertTrue(newPassUrl.contains(jo.getString("token")));
    }

    protected RegisterRequest register() {
        RegisterRequest request = new RegisterRequest();
        request.email = "artem@gcplot.com";
        request.username = "admin";
        request.password = "root";
        return request;
    }

}
