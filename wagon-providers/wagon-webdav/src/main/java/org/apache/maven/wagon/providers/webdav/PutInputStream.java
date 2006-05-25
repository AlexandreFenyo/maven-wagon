package org.apache.maven.wagon.providers.webdav;

/*
 * Copyright 2001-2006 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.events.TransferEventSupport;
import org.apache.maven.wagon.resource.Resource;

/**
 * Put Input Stream is borrowed from wagon-http, and is used to properly
 * notify the listeners of transfer events on a put request.
 * 
 * @author <a href="mailto:joakim@erdfelt.com">Joakim Erdfelt</a>
 */
public class PutInputStream
    extends FileInputStream
{
    private TransferEventSupport eventSupport;

    private TransferEvent event;

    public PutInputStream( File file, Resource resource, Wagon wagon, TransferEventSupport eventSupport )
        throws FileNotFoundException
    {
        super( file );

        this.eventSupport = eventSupport;

        event = new TransferEvent( wagon, resource, TransferEvent.TRANSFER_PROGRESS, TransferEvent.REQUEST_PUT );

        event.setLocalFile( file );
    }

    public int read( byte b[] )
        throws IOException
    {
        return read( b, 0, b.length );
    }

    public int read()
        throws IOException
    {
        byte b[] = new byte[1];

        return read( b );
    }

    public int read( byte b[], int off, int len )
        throws IOException
    {
        int retValue = super.read( b, off, len );

        if ( retValue > 0 )
        {
            event.setTimestamp( System.currentTimeMillis() );

            eventSupport.fireTransferProgress( event, b, retValue );
        }
        return retValue;
    }
}