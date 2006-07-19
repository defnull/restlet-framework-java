/*
 * Copyright 2005-2006 Noelios Consulting.
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the "License").  You may not use this file except
 * in compliance with the License.
 *
 * You can obtain a copy of the license at
 * http://www.opensource.org/licenses/cddl1.txt
 * See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * HEADER in each file and include the License file at
 * http://www.opensource.org/licenses/cddl1.txt
 * If applicable, add the following below this CDDL
 * HEADER, with the fields enclosed by brackets "[]"
 * replaced with your own identifying information:
 * Portions Copyright [yyyy] [name of copyright owner]
 */

package com.noelios.restlet.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.restlet.Call;
import org.restlet.connector.ConnectorCall;
import org.restlet.data.CookieSetting;
import org.restlet.data.DefaultEncoding;
import org.restlet.data.DefaultLanguage;
import org.restlet.data.DefaultMediaType;
import org.restlet.data.Encoding;
import org.restlet.data.Encodings;
import org.restlet.data.Language;
import org.restlet.data.MediaType;
import org.restlet.data.Parameter;
import org.restlet.data.Representation;

import com.noelios.restlet.data.InputRepresentation;
import com.noelios.restlet.data.ReadableRepresentation;
import com.noelios.restlet.util.CookieUtils;
import com.noelios.restlet.util.SecurityUtils;

/**
 * Base HTTP server connector call.
 * @author Jerome Louvel (contact@noelios.com) <a href="http://www.noelios.com/">Noelios Consulting</a>
 */
public abstract class HttpServerCall extends ConnectorCallImpl
{
   /** Obtain a suitable logger. */
   private static Logger logger = Logger.getLogger(HttpServerCall.class.getCanonicalName());
   
   /**
    * Sends the response headers.<br/>
    * Must be called before sending the response output.
    */
   public abstract void sendResponseHeaders() throws IOException;
   
   /**
    * Returns the request entity channel if it exists.
    * @return The request entity channel if it exists.
    */
   public abstract ReadableByteChannel getRequestChannel();
   
   /**
    * Returns the request entity stream if it exists.
    * @return The request entity stream if it exists.
    */
   public abstract InputStream getRequestStream();

   /**
    * Sets the response status code.
    * @param code The response status code.
    * @param reason The response reason phrase.
    */
   public abstract void setResponseStatus(int code, String reason);

   /**
    * Returns the response channel if it exists.
    * @return The response channel if it exists.
    */
   public abstract WritableByteChannel getResponseChannel();
   
   /**
    * Returns the response stream if it exists.
    * @return The response stream if it exists.
    */
   public abstract OutputStream getResponseStream();
   
   /**
    * Converts to an uniform call.
    * @return An equivalent uniform call.
    */
   public Call toUniform()
   {
      return new HttpServerRestletCall(this);
   }

   /**
    * Returns the request input representation if available.
    * @return The request input representation if available.
    */
   public Representation getRequestInput()
   {
   	Representation result = null;
      InputStream requestStream = getRequestStream();
      ReadableByteChannel requestChannel = getRequestChannel();
      
      if((requestStream != null || requestChannel != null))
      {
         // Extract the header values
         Encoding contentEncoding = null;
         Language contentLanguage = null;
         MediaType contentType = null;
         long contentLength = -1L;

         for(Parameter header : getRequestHeaders())
         {
            if(header.getName().equalsIgnoreCase(ConnectorCall.HEADER_CONTENT_ENCODING))
            {
               contentEncoding = new DefaultEncoding(header.getValue());
            }
            else if(header.getName().equalsIgnoreCase(ConnectorCall.HEADER_CONTENT_LANGUAGE))
            {
               contentLanguage = new DefaultLanguage(header.getValue());
            }
            else if(header.getName().equalsIgnoreCase(ConnectorCall.HEADER_CONTENT_TYPE))
            {
               contentType = new DefaultMediaType(header.getValue());
            }
            else if(header.getName().equalsIgnoreCase(ConnectorCall.HEADER_CONTENT_LENGTH))
            {
            	contentLength = Long.parseLong(header.getValue());
            }
         }

         if(requestStream != null)
         {
         	result = new InputRepresentation(requestStream, contentType, contentLength);
         }
         else if(requestChannel != null)
         {
         	result = new ReadableRepresentation(requestChannel, contentType, contentLength);
         }
         
         result.setEncoding(contentEncoding);
         result.setLanguage(contentLanguage);
      }
   	
      return result;
   }
   
   /**
    * Sets the response from a Restlet call.<br>
    * Sets the response headers and the response status. 
    * @param call The call to update from.
    */
   public void setResponse(Call call)
   {
      try
      {
         // Add the cookie settings
         List<CookieSetting> cookies = call.getCookieSettings();
         for(int i = 0; i < cookies.size(); i++)
         {
            getResponseHeaders().add(ConnectorCall.HEADER_SET_COOKIE, CookieUtils.format(cookies.get(i)));
         }
         
         // Set the redirection URI
         if(call.getOutputRef() != null)
         {
         	getResponseHeaders().add(HEADER_LOCATION, call.getOutputRef().toString());
         }

         // Set the security data
         if(call.getSecurity().getChallengeRequest() != null)
         {
         	getResponseHeaders().add(HEADER_WWW_AUTHENTICATE, SecurityUtils.format(call.getSecurity().getChallengeRequest()));
         }

         // Set the server name again
         getResponseHeaders().add(HEADER_SERVER, call.getServerName());
         
         // Set the status code in the response
         if(call.getStatus() != null)
         {
            setResponseStatus(call.getStatus().getCode(), call.getStatus().getDescription());
         }
   
         // If an output was set during the call, copy it to the output stream;
         if(call.getOutput() != null)
         {
         	Representation output = call.getOutput();
   
            if(output.getExpirationDate() != null)
            {
            	getResponseHeaders().add(HEADER_EXPIRES, formatDate(output.getExpirationDate(), false));
            }
            
            if((output.getEncoding() != null) && (!output.getEncoding().equals(Encodings.IDENTITY)))
            {
            	getResponseHeaders().add(HEADER_CONTENT_ENCODING, output.getEncoding().getName());
            }
            
            if(output.getLanguage() != null)
            {
            	getResponseHeaders().add(HEADER_CONTENT_LANGUAGE, output.getLanguage().getName());
            }
            
            if(output.getMediaType() != null)
            {
               StringBuilder contentType = new StringBuilder(output.getMediaType().getName());
   
               if(output.getCharacterSet() != null)
               {
                  // Specify the character set parameter
                  contentType.append("; charset=").append(output.getCharacterSet().getName());
               }
   
               getResponseHeaders().add(HEADER_CONTENT_TYPE, contentType.toString());
            }
   
            if(output.getModificationDate() != null)
            {
            	getResponseHeaders().add(HEADER_LAST_MODIFIED, formatDate(output.getModificationDate(), false));
            }
   
            if(output.getTag() != null)
            {
            	getResponseHeaders().add(HEADER_ETAG, output.getTag().getName());
            }
            
            if(call.getOutput().getSize() != Representation.UNKNOWN_SIZE)
            {
            	getResponseHeaders().add(HEADER_CONTENT_LENGTH, Long.toString(call.getOutput().getSize()));
            }
         }
      }
      catch(Exception e)
      {
         logger.log(Level.INFO, "Exception intercepted", e);
         setResponseStatus(500, "An unexpected exception occured");
      }
   }

   /**
    * Sends the response output.
    * @param output The response output;
    */
   public void sendResponseOutput(Representation output) throws IOException
   {
      if(output != null)
      {
         // Send the output to the client
         output.write(getResponseStream());
      }
      
      getResponseStream().flush();
   }
   
}

