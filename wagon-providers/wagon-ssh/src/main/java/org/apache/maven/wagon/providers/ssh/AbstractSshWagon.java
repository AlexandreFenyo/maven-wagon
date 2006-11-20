package org.apache.maven.wagon.providers.ssh;

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

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Proxy;
import com.jcraft.jsch.ProxyHTTP;
import com.jcraft.jsch.ProxySOCKS5;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;
import org.apache.maven.wagon.AbstractWagon;
import org.apache.maven.wagon.CommandExecutionException;
import org.apache.maven.wagon.CommandExecutor;
import org.apache.maven.wagon.PermissionModeUtils;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.WagonConstants;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.providers.ssh.interactive.InteractiveUserInfo;
import org.apache.maven.wagon.providers.ssh.interactive.NullInteractiveUserInfo;
import org.apache.maven.wagon.providers.ssh.interactive.UserInfoUIKeyboardInteractiveProxy;
import org.apache.maven.wagon.providers.ssh.knownhost.KnownHostChangedException;
import org.apache.maven.wagon.providers.ssh.knownhost.KnownHostsProvider;
import org.apache.maven.wagon.providers.ssh.knownhost.UnknownHostException;
import org.apache.maven.wagon.repository.RepositoryPermissions;
import org.apache.maven.wagon.resource.Resource;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringInputStream;
import org.codehaus.plexus.util.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Common SSH operations.
 *
 * @author <a href="mailto:brett@apache.org">Brett Porter</a>
 * @version $Id$
 * @todo cache pass[words|phases]
 */
public abstract class AbstractSshWagon
    extends AbstractWagon
    implements CommandExecutor, SshWagon
{
    public static final int DEFAULT_SSH_PORT = 22;

    public static final int SOCKS5_PROXY_PORT = 1080;

    protected Session session;

    public static final String EXEC_CHANNEL = "exec";

    private static final int LINE_BUFFER_SIZE = 8192;

    private static final byte LF = '\n';

    private KnownHostsProvider knownHostsProvider;

    private InteractiveUserInfo interactiveUserInfo;

    private UIKeyboardInteractive uIKeyboardInteractive;

    class Streams
    {
        String out = "";

        String err = "";
    }

    public void openConnection()
        throws AuthenticationException
    {
        if ( authenticationInfo == null )
        {
            authenticationInfo = new AuthenticationInfo();
        }

        if ( authenticationInfo.getUserName() == null )
        {
            authenticationInfo.setUserName( System.getProperty( "user.name" ) );
        }

        int port = getRepository().getPort();

        if ( port == WagonConstants.UNKNOWN_PORT )
        {
            port = DEFAULT_SSH_PORT;
        }

        String host = getRepository().getHost();

        // If user don't define a password, he want to use a private key
        File privateKey = null;
        if ( authenticationInfo.getPassword() == null )
        {

            if ( authenticationInfo.getPrivateKey() != null )
            {
                privateKey = new File( authenticationInfo.getPrivateKey() );
            }
            else
            {
                privateKey = findPrivateKey();
            }

            if ( privateKey.exists() )
            {
                if ( authenticationInfo.getPassphrase() == null )
                {
                    authenticationInfo.setPassphrase( "" );
                }

                fireSessionDebug( "Using private key: " + privateKey );
            }
        }

        if ( !interactive )
        {
            interactiveUserInfo = new NullInteractiveUserInfo();
            uIKeyboardInteractive = null;
        }

        initJsch( privateKey, host, port );
    }

    private void initJsch( File privateKey, String host, int port )
        throws AuthenticationException
    {
        JSch sch = new JSch();

        if ( privateKey != null && privateKey.exists() )
        {
            try
            {
                sch.addIdentity( privateKey.getAbsolutePath(), authenticationInfo.getPassphrase() );
            }
            catch ( JSchException e )
            {
                fireSessionError( e );

                throw new AuthenticationException( "Cannot connect. Reason: " + e.getMessage(), e );
            }
        }

        try
        {
            session = sch.getSession( authenticationInfo.getUserName(), host, port );
        }
        catch ( JSchException e )
        {
            fireSessionError( e );

            throw new AuthenticationException( "Cannot connect. Reason: " + e.getMessage(), e );
        }

        if ( proxyInfo != null && proxyInfo.getHost() != null )
        {
            Proxy proxy;

            int proxyPort = proxyInfo.getPort();

            // HACK: if port == 1080 we will use SOCKS5 Proxy, otherwise will use HTTP Proxy
            if ( proxyPort == SOCKS5_PROXY_PORT )
            {
                proxy = new ProxySOCKS5( proxyInfo.getHost() );
                ( (ProxySOCKS5) proxy ).setUserPasswd( proxyInfo.getUserName(), proxyInfo.getPassword() );
            }
            else
            {
                proxy = new ProxyHTTP( proxyInfo.getHost(), proxyPort );
                ( (ProxyHTTP) proxy ).setUserPasswd( proxyInfo.getUserName(), proxyInfo.getPassword() );
            }

            session.setProxy( proxy );
        }
        else
        {
            session.setProxy( null );
        }

        // username and password will be given via UserInfo interface.
        UserInfo ui = new WagonUserInfo( authenticationInfo, interactiveUserInfo );

        if ( uIKeyboardInteractive != null )
        {
            ui = new UserInfoUIKeyboardInteractiveProxy( ui, uIKeyboardInteractive );
        }

        Properties config = new Properties();
        if ( knownHostsProvider != null )
        {
            try
            {
                String contents = knownHostsProvider.getContents();
                if ( contents != null )
                {
                    sch.setKnownHosts( new StringInputStream( contents ) );
                }
            }
            catch ( JSchException e )
            {
                fireSessionError( e );
                // continue without known_hosts
            }
            config.setProperty( "StrictHostKeyChecking", knownHostsProvider.getHostKeyChecking() );
        }

        config.setProperty( "BatchMode", interactive ? "no" : "yes" );

        session.setConfig( config );

        session.setUserInfo( ui );

        StringWriter stringWriter = new StringWriter();
        try
        {
            session.connect();

            if ( knownHostsProvider != null )
            {
                PrintWriter w = new PrintWriter( stringWriter );

                HostKeyRepository hkr = sch.getHostKeyRepository();
                HostKey[] keys = hkr.getHostKey();

                for ( int i = 0; i < keys.length; i++ )
                {
                    HostKey key = keys[i];
                    w.println( key.getHost() + " " + key.getType() + " " + key.getKey() );
                }
            }
        }
        catch ( JSchException e )
        {
            fireSessionError( e );

            if ( e.getMessage().startsWith( "UnknownHostKey:" ) || e.getMessage().startsWith( "reject HostKey:" ) )
            {
                throw new UnknownHostException( host, e );
            }
            else if ( e.getMessage().indexOf( "HostKey has been changed" ) >= 0 )
            {
                throw new KnownHostChangedException( host, e );
            }
            else
            {
                throw new AuthenticationException( "Cannot connect. Reason: " + e.getMessage(), e );
            }
        }

        try
        {
            knownHostsProvider.storeKnownHosts( stringWriter.toString() );
        }
        catch ( IOException e )
        {
            closeConnection();

            fireSessionError( e );

            throw new AuthenticationException(
                "Connection aborted - failed to write to known_hosts. Reason: " + e.getMessage(), e );
        }
    }

    private File findPrivateKey()
    {
        String privateKeyDirectory = System.getProperty( "wagon.privateKeyDirectory" );

        if ( privateKeyDirectory == null )
        {
            privateKeyDirectory = System.getProperty( "user.home" );
        }

        File privateKey = new File( privateKeyDirectory, ".ssh/id_dsa" );

        if ( !privateKey.exists() )
        {
            privateKey = new File( privateKeyDirectory, ".ssh/id_rsa" );
        }

        return privateKey;
    }

    public Streams executeCommand( String command, boolean ignoreFailures )
        throws CommandExecutionException
    {
        fireTransferDebug( "Executing command: " + command );

        ChannelExec channel = null;
        BufferedReader stdoutReader = null;
        BufferedReader stderrReader = null;
        try
        {
            channel = (ChannelExec) session.openChannel( EXEC_CHANNEL );

            channel.setCommand( command + "\n" );

            InputStream stdout = channel.getInputStream();
            InputStream stderr = channel.getErrStream();

            channel.connect();

            stdoutReader = new BufferedReader( new InputStreamReader( stdout ) );
            stderrReader = new BufferedReader( new InputStreamReader( stderr ) );

            Streams streams = processStreams( stderrReader, stdoutReader );

            if ( streams.err.length() > 0 )
            {
                int exitCode = channel.getExitStatus();
                throw new CommandExecutionException( "Exit code: " + exitCode + " - " + streams.err );
            }

            return streams;
        }
        catch ( IOException e )
        {
            throw new CommandExecutionException( "Cannot execute remote command: " + command, e );
        }
        catch ( JSchException e )
        {
            throw new CommandExecutionException( "Cannot execute remote command: " + command, e );
        }
        finally
        {
            IOUtil.close( stdoutReader );
            IOUtil.close( stderrReader );
            if ( channel != null )
            {
                channel.disconnect();
            }
        }
    }

    private Streams processStreams( BufferedReader stderrReader, BufferedReader stdoutReader )
        throws IOException, CommandExecutionException
    {
        Streams streams = new Streams();

        while ( true )
        {
            String line = stderrReader.readLine();
            if ( line == null )
            {
                break;
            }

            // TODO: I think we need to deal with exit codes instead, but IIRC there are some cases of errors that don't have exit codes
            // ignore this error. TODO: output a warning
            if ( !line.startsWith( "Could not chdir to home directory" ) &&
                !line.endsWith( "ttyname: Operation not supported" ) )
            {
                streams.err += line + "\n";
            }
        }

        while ( true )
        {
            String line = stdoutReader.readLine();
            if ( line == null )
            {
                break;
            }

            streams.out += line + "\n";
        }

        // drain the output stream.
        // TODO: we'll save this for the 1.0-alpha-8 line, so we can test it more. the -q arg in the
        // unzip command should keep us until then...
//            int avail = in.available();
//            byte[] trashcan = new byte[1024];
//
//            while( ( avail = in.available() ) > 0 )
//            {
//                in.read( trashcan, 0, avail );
//            }

        return streams;
    }

    public void executeCommand( String command )
        throws CommandExecutionException
    {
        executeCommand( command, false );
    }

    protected String readLine( InputStream in )
        throws IOException
    {
        StringBuffer sb = new StringBuffer();

        while ( true )
        {
            if ( sb.length() > LINE_BUFFER_SIZE )
            {
                throw new IOException( "Remote server sent a too long line" );
            }

            int c = in.read();

            if ( c < 0 )
            {
                throw new IOException( "Remote connection terminated unexpectedly." );
            }

            if ( c == LF )
            {
                break;
            }

            sb.append( (char) c );
        }
        return sb.toString();
    }

    protected static void sendEom( OutputStream out )
        throws IOException
    {
        out.write( 0 );

        out.flush();
    }

    public void closeConnection()
    {
        if ( session != null )
        {
            session.disconnect();
            session = null;
        }
    }

    protected void handleGetException( Resource resource, Exception e, File destination )
        throws TransferFailedException, ResourceDoesNotExistException
    {
        fireTransferError( resource, e, TransferEvent.REQUEST_GET );

        if ( destination.exists() )
        {
            boolean deleted = destination.delete();

            if ( !deleted )
            {
                destination.deleteOnExit();
            }
        }

        String msg = "Error occured while downloading '" + resource + "' from the remote repository:" + getRepository();

        throw new TransferFailedException( msg, e );
    }

    private static class WagonUserInfo
        implements UserInfo
    {
        private final InteractiveUserInfo userInfo;

        private String password;

        private String passphrase;

        WagonUserInfo( AuthenticationInfo authInfo, InteractiveUserInfo userInfo )
        {
            this.userInfo = userInfo;

            this.password = authInfo.getPassword();

            this.passphrase = authInfo.getPassphrase();
        }

        public String getPassphrase()
        {
            return passphrase;
        }

        public String getPassword()
        {
            return password;
        }

        public boolean promptPassphrase( String arg0 )
        {
            if ( passphrase == null && userInfo != null )
            {
                passphrase = userInfo.promptPassphrase( arg0 );
            }
            return passphrase != null;
        }

        public boolean promptPassword( String arg0 )
        {
            if ( password == null && userInfo != null )
            {
                password = userInfo.promptPassword( arg0 );
            }
            return password != null;
        }

        public boolean promptYesNo( String arg0 )
        {
            if ( userInfo != null )
            {
                return userInfo.promptYesNo( arg0 );
            }
            else
            {
                return false;
            }
        }

        public void showMessage( String message )
        {
            if ( userInfo != null )
            {
                userInfo.showMessage( message );
            }
        }
    }

    public final KnownHostsProvider getKnownHostsProvider()
    {
        return knownHostsProvider;
    }

    public final void setKnownHostsProvider( KnownHostsProvider knownHostsProvider )
    {
        if ( knownHostsProvider == null )
        {
            throw new IllegalArgumentException( "knownHostsProvider can't be null" );
        }
        this.knownHostsProvider = knownHostsProvider;
    }

    public InteractiveUserInfo getInteractiveUserInfo()
    {
        return interactiveUserInfo;
    }

    public void setInteractiveUserInfo( InteractiveUserInfo interactiveUserInfo )
    {
        if ( interactiveUserInfo == null )
        {
            throw new IllegalArgumentException( "interactiveUserInfo can't be null" );
        }
        this.interactiveUserInfo = interactiveUserInfo;
    }

    public void putDirectory( File sourceDirectory, String destinationDirectory )
        throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException
    {
        String basedir = getRepository().getBasedir();

        destinationDirectory = StringUtils.replace( destinationDirectory, "\\", "/" );

        String path = getPath( basedir, destinationDirectory );
        try
        {
            if ( getRepository().getPermissions() != null )
            {
                String dirPerms = getRepository().getPermissions().getDirectoryMode();

                if ( dirPerms != null )
                {
                    String umaskCmd = "umask " + PermissionModeUtils.getUserMaskFor( dirPerms );
                    executeCommand( umaskCmd );
                }
            }

            String mkdirCmd = "mkdir -p " + path;

            executeCommand( mkdirCmd );
        }
        catch ( CommandExecutionException e )
        {
            throw new TransferFailedException( "Error performing commands for file transfer", e );
        }

        File zipFile;
        try
        {
            zipFile = File.createTempFile( "wagon", ".zip" );
            zipFile.deleteOnExit();

            List files = FileUtils.getFileNames( sourceDirectory, "**/**", "", false );

            createZip( files, zipFile, sourceDirectory );
        }
        catch ( IOException e )
        {
            throw new TransferFailedException( "Unable to create ZIP archive of directory", e );
        }

        put( zipFile, getPath( destinationDirectory, zipFile.getName() ) );

        try
        {
            executeCommand( "cd " + path + "; unzip -q -o " + zipFile.getName() + "; rm -f " + zipFile.getName() );

            zipFile.delete();

            RepositoryPermissions permissions = getRepository().getPermissions();

            if ( permissions != null && permissions.getGroup() != null )
            {
                executeCommand( "chgrp -Rf " + permissions.getGroup() + " " + path );
            }

            if ( permissions != null && permissions.getFileMode() != null )
            {
                executeCommand( "chmod -Rf " + permissions.getFileMode() + " " + path );
            }
        }
        catch ( CommandExecutionException e )
        {
            throw new TransferFailedException( "Error performing commands for file transfer", e );
        }
    }

    public boolean supportsDirectoryCopy()
    {
        return true;
    }

    public List getFileList( String destinationDirectory )
        throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException
    {
        try
        {
            String path = getPath( getRepository().getBasedir(), destinationDirectory );
            Streams streams = executeCommand( "ls -la " + path, true );

            BufferedReader br = new BufferedReader( new StringReader( streams.out ) );

            List ret = new ArrayList();
            String line = br.readLine();

            while ( line != null )
            {
                String[] parts = StringUtils.split( line, " " );
                if ( parts.length >= 8 )
                {
                    ret.add( parts[8] );
                }

                line = br.readLine();
            }

            return ret;
        }
        catch ( CommandExecutionException e )
        {
            if ( e.getMessage().trim().endsWith( "No such file or directory" ) )
            {
                throw new ResourceDoesNotExistException( e.getMessage().trim() );
            }
            else
            {
                throw new TransferFailedException( "Error performing file listing.", e );
            }
        }
        catch ( IOException e )
        {
            throw new TransferFailedException( "Error parsing file listing.", e );
        }
    }

    public boolean resourceExists( String resourceName )
        throws TransferFailedException, AuthorizationException
    {
        try
        {
            String path = getPath( getRepository().getBasedir(), resourceName );
            executeCommand( "ls " + path );

            // Parsing of output not really needed.  As a failed ls results in a
            // CommandExectionException on the 'ls' command.

            return true;
        }
        catch ( CommandExecutionException e )
        {
            // Error?  Then the 'ls' command failed.  No such file found.
            return false;
        }
    }
}