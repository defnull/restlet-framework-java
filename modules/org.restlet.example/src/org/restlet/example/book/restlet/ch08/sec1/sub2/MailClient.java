package org.restlet.example.book.restlet.ch08.sec1.sub2;

import org.restlet.data.Cookie;
import org.restlet.data.Form;
import org.restlet.resource.ClientResource;

/**
 * Mail client updating a mail by submitting a form.
 */
public class MailClient {

    public static void main(String[] args) throws Exception {
        ClientResource mailClient = new ClientResource(
                "http://localhost:8082/accounts/123/mails/abc");

        mailClient.getRequest().getCookies()
                .add(new Cookie("Credentials", "scott=tiger"));

        Form form = new Form();
        form.add("subject", "Message to J�r�me");
        form.add("content", "Doh!\n\nAllo?");
        mailClient.put(form).write(System.out);
    }

}