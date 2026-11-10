package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Durable real files for a game whose shared-storage path is blocked but whose SAF tree is readable. */
public final class SystemNnnGameImport {
    /** Throw CancellationException to cancel; no partially copied directory becomes runnable. */
    public interface Cancellation { void check(); }
    public enum Reason { NO_GRANT, SOURCE_MISSING, INVALID_SOURCE, INCOMPLETE_SOURCE, NO_SPACE,
        COPY_FAILED, COMMIT_FAILED, CORRUPT_COPY, BUSY }
    public static final class Failure extends IOException {
        public final Reason reason;
        Failure(Reason reason){super(reason.name());this.reason=reason;}
        Failure(Reason reason,Throwable cause){super(reason.name(),cause);this.reason=reason;}
    }
    private static final String PROVIDER="com.android.externalstorage.documents";
    private static final String DIRECTORY="systemnnn-imports",IDENTITY="source.id",GAME="game",EVICTED="resources-evicted";
    private static final Pattern COPY_KEY=Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_ENTRIES=100000,MAX_DEPTH=64,MAX_ID_BYTES=8192,CHUNK=64*1024;
    private static final long FREE_HEADROOM=256L*1024*1024;
    private static final Cancellation NONE=()->{};
    private static final Set<Path> OPEN_COPIES=ConcurrentHashMap.newKeySet();
    private static final String[] COLUMNS={DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
    private SystemNnnGameImport(){}

    /** A primary: document id is independent of the granting ancestor and the local Android user number. */
    public static String sourceIdentity(Uri directory) throws Failure {
        if(directory==null||!"content".equals(directory.getScheme())||!PROVIDER.equals(directory.getAuthority()))
            throw new Failure(Reason.INVALID_SOURCE);
        String id;
        try{id=DocumentsContract.getDocumentId(directory);}
        catch(IllegalArgumentException e){
            try{id=DocumentsContract.getTreeDocumentId(directory);}
            catch(IllegalArgumentException bad){throw new Failure(Reason.INVALID_SOURCE,bad);}
        }
        return normalizeIdentity(id);
    }
    static String normalizeIdentity(String id) throws Failure {
        if(id==null||id.getBytes(StandardCharsets.UTF_8).length>MAX_ID_BYTES)throw new Failure(Reason.INVALID_SOURCE);
        int colon=id.indexOf(':');
        if(colon<=0)throw new Failure(Reason.INVALID_SOURCE);
        String volume=id.substring(0,colon),relative=id.substring(colon+1);
        if(!volume.matches("[A-Za-z0-9_-]+")||relative.indexOf('\\')>=0||relative.indexOf('\0')>=0)
            throw new Failure(Reason.INVALID_SOURCE);
        if(volume.equalsIgnoreCase("primary"))volume="primary";
        if(!relative.isEmpty())for(String part:relative.split("/",-1))checkName(part);
        return volume+":"+relative;
    }
    private static boolean contains(String ancestor,String child){
        return child.equals(ancestor)||child.startsWith(ancestor+(ancestor.endsWith(":")?"":"/"));
    }
    /** Returns a document URI rooted in the nearest persisted read grant, or null when reauthorization is needed. */
    public static Uri findAuthorizedSource(Context context,Uri directory) throws Failure {
        String identity=sourceIdentity(directory);Uri best=null;int length=-1;
        for(UriPermission permission:context.getContentResolver().getPersistedUriPermissions()){
            if(!permission.isReadPermission())continue;
            Uri tree=permission.getUri();
            if(!"content".equals(tree.getScheme())||!PROVIDER.equals(tree.getAuthority()))continue;
            try{
                String parent=normalizeIdentity(DocumentsContract.getTreeDocumentId(tree));
                if(contains(parent,identity)&&parent.length()>length){best=tree;length=parent.length();}
            }catch(IllegalArgumentException|Failure ignored){}
        }
        return best==null?null:DocumentsContract.buildDocumentUriUsingTree(best,identity);
    }
    /** Null means no committed copy. A damaged committed copy never silently falls back to another sav directory. */
    public static File committedRoot(Context context,String sourceId) throws IOException {
        File external=context.getExternalFilesDir(null);
        if(external==null)throw new Failure(Reason.COPY_FAILED);
        return committedRoot(new File(external,DIRECTORY),normalizeIdentity(sourceId));
    }
    static File committedRoot(File base,String sourceId) throws IOException {
        if(Files.notExists(base.toPath(),LinkOption.NOFOLLOW_LINKS))return null;
        plainDirectory(base);
        File target=new File(base,key(sourceId));
        if(Files.notExists(target.toPath(),LinkOption.NOFOLLOW_LINKS))return null;
        plainDirectory(target);
        if(!sourceId.equals(readIdentity(target)))throw new Failure(Reason.CORRUPT_COPY);
        File game=new File(target,GAME);
        plainDirectory(game);
        return game;
    }
    private static Path evictionMarker(File game){return new File(game.getParentFile(),EVICTED).toPath();}
    public static boolean resourcesEvicted(File game){
        File entry=game.getParentFile(),base=entry==null?null:entry.getParentFile();
        return GAME.equals(game.getName())&&base!=null&&DIRECTORY.equals(base.getName())&&
            COPY_KEY.matcher(entry.getName()).matches()&&!Files.notExists(evictionMarker(game),LinkOption.NOFOLLOW_LINKS);
    }
    public static boolean hasImportedResources(File game) throws IOException {
        return !resourceChildren(game).isEmpty();
    }
    public static List<String> importedResourceSourceIds(Context context) throws IOException {
        File external=context.getExternalFilesDir(null);
        if(external==null)throw new Failure(Reason.COPY_FAILED);
        return importedResourceSourceIds(new File(external,DIRECTORY));
    }
    static List<String> importedResourceSourceIds(File base) throws IOException {
        if(Files.notExists(base.toPath(),LinkOption.NOFOLLOW_LINKS))return Collections.emptyList();
        plainDirectory(base);
        File[] entries=base.listFiles();
        if(entries==null)throw new Failure(Reason.COPY_FAILED);
        List<String> sources=new ArrayList<>();
        for(File entry:entries){
            if(!COPY_KEY.matcher(entry.getName()).matches()||
                !Files.isDirectory(entry.toPath(),LinkOption.NOFOLLOW_LINKS))continue;
            try{
                String sourceId=readIdentity(entry);
                if(!sourceId.equals(normalizeIdentity(sourceId))||!entry.getName().equals(key(sourceId)))continue;
                if(hasImportedResources(new File(entry,GAME)))sources.add(sourceId);
            }catch(IOException|SecurityException ignored){
                // An entry whose identity or layout cannot be verified is ineligible for eviction.
            }
        }
        return sources;
    }
    public static boolean clearResources(Context context,String sourceId) throws IOException {
        File external=context.getExternalFilesDir(null);
        if(external==null)throw new Failure(Reason.COPY_FAILED);
        return clearResources(new File(external,DIRECTORY),sourceId);
    }
    static boolean clearResources(File base,String sourceId) throws IOException {
        String identity=normalizeIdentity(sourceId);
        if(committedRoot(base,identity)==null)return false;
        return withImportLock(base,identity,()->{
            File game=committedRoot(base,identity);
            if(game==null)return false;
            List<File> resources=resourceChildren(game);
            if(resources.isEmpty())return false;
            try(GameSavePaths.Access access=GameSavePaths.forGame(game).lock()){
                Path marker=evictionMarker(game);
                if(Files.notExists(marker,LinkOption.NOFOLLOW_LINKS))Files.createFile(marker);
                else if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS))throw new Failure(Reason.CORRUPT_COPY);
                for(File resource:resources)deleteOwnedTree(resource);
            }
            return true;
        });
    }
    private static List<File> resourceChildren(File game) throws IOException {
        plainDirectory(game);
        File[] children=game.listFiles();
        if(children==null)throw new Failure(Reason.COPY_FAILED);
        List<File> resources=new ArrayList<>();
        boolean sawSaves=false;
        for(File child:children){
            if(!child.getName().equalsIgnoreCase("sav")){resources.add(child);continue;}
            if(sawSaves)throw new Failure(Reason.CORRUPT_COPY);
            plainDirectory(child);
            sawSaves=true;
        }
        return resources;
    }
    private static String readIdentity(File entry) throws IOException {
        Path id=new File(entry,IDENTITY).toPath();
        if(!Files.isRegularFile(id,LinkOption.NOFOLLOW_LINKS)||Files.size(id)>MAX_ID_BYTES)
            throw new Failure(Reason.CORRUPT_COPY);
        ByteArrayOutputStream identity=new ByteArrayOutputStream();
        try(InputStream input=Files.newInputStream(id,LinkOption.NOFOLLOW_LINKS)){
            for(int value;(value=input.read())!=-1;){
                if(identity.size()>=MAX_ID_BYTES)throw new Failure(Reason.CORRUPT_COPY);
                identity.write(value);
            }
        }
        return new String(identity.toByteArray(),StandardCharsets.UTF_8);
    }
    /** Legacy private saves and raw settings were keyed by the original canonical path. */
    static File originalGame(Context context,File game) throws IOException {
        File entry=game.getParentFile();
        File base=entry==null?null:entry.getParentFile();
        if(!GAME.equals(game.getName())||base==null||!DIRECTORY.equals(base.getName()))return game;
        File external=context.getExternalFilesDir(null);
        if(external==null||!base.equals(new File(external,DIRECTORY).getCanonicalFile()))return game;
        String identity=normalizeIdentity(readIdentity(entry));
        if(!entry.getName().equals(key(identity)))throw new Failure(Reason.CORRUPT_COPY);
        int colon=identity.indexOf(':');
        String volume=identity.substring(0,colon);
        File directory="primary".equals(volume)?Environment.getExternalStorageDirectory():new File("/storage",volume);
        return new File(directory,identity.substring(colon+1)).getCanonicalFile();
    }
    /** Open actual bytes: canRead/stat alone do not establish access on scoped-storage devices. */
    public static boolean readableBootstrap(File root){
        try{
            if(resourcesEvicted(root))return false;
            File spt=child(root,"spt",true),script=spt==null?null:child(spt,"sys.spt",false);
            if(script==null||Files.isSymbolicLink(spt.toPath())||Files.isSymbolicLink(script.toPath()))return false;
            try(InputStream input=new FileInputStream(script)){return input.read()!=-1;}
        }catch(IOException|SecurityException e){return false;}
    }
    private static File child(File parent,String name,boolean directory){
        if(parent==null)return null;
        File exact=new File(parent,name);
        if(directory?exact.isDirectory():exact.isFile())return exact;
        File[] entries=parent.listFiles();File found=null;
        if(entries!=null)for(File entry:entries)
            if(entry.getName().equalsIgnoreCase(name)&&(directory?entry.isDirectory():entry.isFile())&&
                (found==null||entry.getName().compareTo(found.getName())<0))found=entry;
        return found;
    }
    /** Imports resources without changing the source or replacing an existing game/sav. */
    public static File importDirectory(Context context,Uri source,String sourceId,boolean allowCreate,Cancellation cancellation) throws IOException {
        Cancellation check=cancellation==null?NONE:cancellation;check.check();
        String identity=normalizeIdentity(sourceId);
        if(!identity.equals(sourceIdentity(source)))throw new Failure(Reason.INVALID_SOURCE);
        File external=context.getExternalFilesDir(null);
        if(external==null)throw new Failure(Reason.COPY_FAILED);
        File base=new File(external,DIRECTORY);
        return importDirectory(base,identity,allowCreate,check,()->{
            Uri granted=findAuthorizedSource(context,source);
            if(granted==null)throw new Failure(Reason.NO_GRANT);
            Source tree=new Source(context,granted,identity,check);
            return (game,restoring,publish)->tree.copyGame(base,game,restoring,publish);
        });
    }

    interface ImportSource {
        // Keep the source save lock held through publication of a fresh copy.
        File copyGame(File game,boolean restoring,CopyOperation<File> publish) throws IOException;
    }
    static File importDirectory(File base,String identity,boolean allowCreate,Cancellation check,CopyOperation<ImportSource> authorize) throws IOException {
        File existing=committedRoot(base,identity);
        if(existing!=null&&!resourcesEvicted(existing))return existing;
        if(existing==null&&!allowCreate)throw new Failure(Reason.CORRUPT_COPY);
        ImportSource source=authorize.run();
        String key=key(identity);
        try{
            return withImportLock(base,identity,()->{
                File current=committedRoot(base,identity);
                boolean restoring=current!=null;
                if(!restoring&&!allowCreate)throw new Failure(Reason.CORRUPT_COPY);
                if(restoring&&!resourcesEvicted(current))return current;
                File[] leftovers=base.listFiles();
                if(leftovers==null)throw new Failure(Reason.COPY_FAILED);
                for(File leftover:leftovers)if(leftover.getName().startsWith(key+".pending-")){
                    check.check();deleteOwnedTree(leftover);
                }
                check.check();
                File staging=new File(base,key+".pending-"+UUID.randomUUID());
                Files.createDirectory(staging.toPath());
                Throwable failure=null;
                try{
                    File game=new File(staging,GAME);Files.createDirectory(game.toPath());
                    return source.copyGame(game,restoring,()->{
                        check.check();
                        if(restoring){
                            restoreResources(current,game);
                            return current;
                        }
                        File metadata=new File(staging,IDENTITY);
                        try(FileOutputStream output=new FileOutputStream(metadata)){
                            output.write(identity.getBytes(StandardCharsets.UTF_8));output.getFD().sync();
                        }
                        try{Files.move(staging.toPath(),new File(base,key).toPath(),StandardCopyOption.ATOMIC_MOVE);}
                        catch(IOException failed){throw new Failure(Reason.COMMIT_FAILED,failed);}
                        return new File(new File(base,key),GAME);
                    });
                }catch(IOException|RuntimeException|Error error){failure=error;throw error;}
                finally{if(Files.exists(staging.toPath(),LinkOption.NOFOLLOW_LINKS))try{deleteOwnedTree(staging);}catch(IOException cleanup){
                    if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup;
                }}
            });
        }catch(Failure e){throw e;}
        catch(CancellationException e){throw e;}
        catch(SecurityException e){throw new Failure(Reason.NO_GRANT,e);}
        catch(IOException e){throw new Failure(Reason.COPY_FAILED,e);}
        catch(RuntimeException e){throw new Failure(Reason.COPY_FAILED,e);}
    }
    // The marker makes an interrupted publish unrunnable while keeping the same save authority.
    private static void restoreResources(File game,File staging) throws IOException {
        Path marker=evictionMarker(game);
        if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS))throw new Failure(Reason.CORRUPT_COPY);
        List<File> old=resourceChildren(game);
        try(GameSavePaths.Access access=GameSavePaths.forGame(game).lock()){
            for(File resource:old)deleteOwnedTree(resource);
            for(File resource:resourceChildren(staging))
                Files.move(resource.toPath(),new File(game,resource.getName()).toPath(),StandardCopyOption.ATOMIC_MOVE);
            Files.delete(marker);
        }catch(SystemNnnHost.SaveBusyException e){throw new Failure(Reason.BUSY,e);}
    }
    public interface CopyOperation<T>{T run() throws IOException;}
    /** Holds the same source lock used for publishing a copy. Resolve and write the active sav inside it. */
    public static <T> T withImportLock(Context context,String sourceId,CopyOperation<T> operation) throws IOException {
        File external=context.getExternalFilesDir(null);
        if(external==null)throw new Failure(Reason.COPY_FAILED);
        return withImportLock(new File(external,DIRECTORY),sourceId,operation);
    }
    static <T> T withImportLock(File base,String sourceId,CopyOperation<T> operation) throws IOException {
        String key=key(normalizeIdentity(sourceId));
        Files.createDirectories(base.toPath());plainDirectory(base);
        Path path=base.getCanonicalFile().toPath().resolve(key+".lock");
        if(!OPEN_COPIES.add(path))throw new Failure(Reason.BUSY);
        try(FileChannel channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            FileLock lock;
            try{lock=channel.tryLock();}catch(OverlappingFileLockException e){lock=null;}
            if(lock==null)throw new Failure(Reason.BUSY);
            try(FileLock held=lock){return operation.run();}
        }finally{OPEN_COPIES.remove(path);}
    }
    private static final class Entry {
        final String id,name;final boolean directory;final long size,modified;
        Entry(Cursor row) throws Failure {
            id=normalizeIdentity(row.getString(0));name=row.getString(1);checkName(name);
            directory=DocumentsContract.Document.MIME_TYPE_DIR.equals(row.getString(2));
            size=row.isNull(3)?-1:row.getLong(3);modified=row.isNull(4)?0:row.getLong(4);
        }
        String stamp(){return id+"\n"+size+"\n"+modified;}
    }
    private static final class Source {
        final Context context;final Uri tree;final String identity;final Cancellation check;
        Source(Context context,Uri tree,String identity,Cancellation check){
            this.context=context;this.tree=tree;this.identity=identity;this.check=check;
        }
        File copyGame(File base,File game,boolean restoring,CopyOperation<File> publish) throws IOException {
            requireRoot();
            List<Entry> roots=children(identity);
            requireBootstrap(roots);
            Entry sav=null;
            if(restoring)roots.removeIf(entry->entry.name.equalsIgnoreCase("sav"));
            for(Entry entry:roots)if(entry.name.equalsIgnoreCase("sav")){
                if(!entry.directory||sav!=null)throw new Failure(Reason.INVALID_SOURCE);sav=entry;
            }
            Map<String,String> savesBefore=saveSnapshot(sav);
            try(SourceLock sourceLock=lockSaves(sav)){
                copy(roots,game,"",0,new Budget(base));
                if(!readableBootstrap(game))throw new Failure(Reason.INVALID_SOURCE);
                if(!restoring){
                    Entry currentSav=null;
                    for(Entry entry:children(identity))if(entry.directory&&entry.name.equalsIgnoreCase("sav")){
                        if(currentSav!=null)throw new Failure(Reason.INVALID_SOURCE);currentSav=entry;
                    }
                    if(!savesBefore.equals(saveSnapshot(currentSav)))throw new Failure(Reason.INCOMPLETE_SOURCE);
                }
                return publish.run();
            }
        }
        Uri uri(String id){return DocumentsContract.buildDocumentUriUsingTree(tree,id);}
        void requireBootstrap(List<Entry> roots) throws IOException {
            Entry spt=null;
            for(Entry entry:roots)if(entry.directory&&entry.name.equalsIgnoreCase("spt")){
                if(spt!=null)throw new Failure(Reason.INVALID_SOURCE);spt=entry;
            }
            if(spt==null)throw new Failure(Reason.INVALID_SOURCE);
            Entry script=null;
            for(Entry entry:children(spt.id))if(!entry.directory&&entry.name.equalsIgnoreCase("sys.spt")){
                if(script!=null)throw new Failure(Reason.INVALID_SOURCE);script=entry;
            }
            if(script==null)throw new Failure(Reason.INVALID_SOURCE);
            try(InputStream input=context.getContentResolver().openInputStream(uri(script.id))){
                if(input==null)throw new Failure(Reason.NO_GRANT);
                if(input.read()==-1)throw new Failure(Reason.INVALID_SOURCE);
            }
        }
        void requireRoot() throws IOException {
            try(Cursor row=context.getContentResolver().query(uri(identity),COLUMNS,null,null,null)){
                if(row==null)throw new Failure(Reason.NO_GRANT);
                if(!row.moveToFirst())throw new Failure(Reason.SOURCE_MISSING);
                Entry root=new Entry(row);
                if(!identity.equals(root.id)||!root.directory)throw new Failure(Reason.INVALID_SOURCE);
            }catch(SecurityException e){throw new Failure(Reason.NO_GRANT,e);}
        }
        List<Entry> children(String id) throws IOException {
            check.check();ArrayList<Entry> result=new ArrayList<>();Set<String> names=new HashSet<>();
            try(Cursor row=context.getContentResolver().query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(tree,id),COLUMNS,null,null,null)){
                if(row==null)throw new Failure(Reason.NO_GRANT);
                while(row.moveToNext()){
                    check.check();Entry entry=new Entry(row);
                    if(!contains(id,entry.id)||id.equals(entry.id)||!names.add(entry.name)||result.size()>=MAX_ENTRIES)
                        throw new Failure(Reason.INVALID_SOURCE);
                    result.add(entry);
                }
            }catch(SecurityException e){throw new Failure(Reason.NO_GRANT,e);}
            return result;
        }
        Map<String,String> saveSnapshot(Entry sav) throws IOException {
            Map<String,String> result=new LinkedHashMap<>();
            if(sav!=null)snapshot(sav.id,"",0,result);
            return result;
        }
        void snapshot(String id,String relative,int depth,Map<String,String> result) throws IOException {
            if(depth>MAX_DEPTH)throw new Failure(Reason.INVALID_SOURCE);
            for(Entry entry:children(id)){
                String path=relative+entry.name;
                if(relative.isEmpty()&&entry.name.equalsIgnoreCase("emberfall.lock"))continue;
                if(entry.name.startsWith("emberfall")&&(entry.name.endsWith(".tmp")||entry.name.endsWith(".new")))
                    throw new Failure(Reason.INCOMPLETE_SOURCE);
                if(relative.isEmpty()&&entry.name.equalsIgnoreCase("emberfall.migrate")){
                    if(!entry.directory||!children(entry.id).isEmpty())throw new Failure(Reason.INCOMPLETE_SOURCE);
                    continue;
                }
                if(result.size()>=MAX_ENTRIES)throw new Failure(Reason.INVALID_SOURCE);
                result.put(path,entry.stamp());
                if(entry.directory)snapshot(entry.id,path+"/",depth+1,result);
            }
        }
        SourceLock lockSaves(Entry sav) throws IOException {
            if(sav==null)return new SourceLock(null,null);
            for(Entry entry:children(sav.id))if(entry.name.equalsIgnoreCase("emberfall.lock")){
                if(entry.directory)throw new Failure(Reason.INVALID_SOURCE);
                ParcelFileDescriptor descriptor;
                try{descriptor=context.getContentResolver().openFileDescriptor(uri(entry.id),"r");}
                catch(SecurityException|java.io.FileNotFoundException e){throw new Failure(Reason.NO_GRANT,e);}
                if(descriptor==null)throw new Failure(Reason.NO_GRANT);
                FileInputStream stream=new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                FileLock lock;
                try{lock=stream.getChannel().tryLock(0,Long.MAX_VALUE,true);}
                catch(IOException|OverlappingFileLockException e){stream.close();throw new Failure(Reason.BUSY,e);}
                if(lock==null){stream.close();throw new Failure(Reason.BUSY);}
                return new SourceLock(stream,lock);
            }
            return new SourceLock(null,null);
        }
        void copy(List<Entry> entries,File destination,String relative,int depth,Budget budget) throws IOException {
            if(depth>MAX_DEPTH)throw new Failure(Reason.INVALID_SOURCE);
            for(Entry entry:entries){
                check.check();if(++budget.entries>MAX_ENTRIES)throw new Failure(Reason.INVALID_SOURCE);
                if(relative.equalsIgnoreCase("sav/")&&entry.name.equalsIgnoreCase("emberfall.lock"))continue;
                if(relative.equalsIgnoreCase("sav/")&&entry.name.equalsIgnoreCase("emberfall.migrate")){
                    if(!entry.directory||!children(entry.id).isEmpty())throw new Failure(Reason.INCOMPLETE_SOURCE);
                    continue;
                }
                File target=new File(destination,entry.name);
                if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS))throw new Failure(Reason.INVALID_SOURCE);
                if(entry.directory){
                    Files.createDirectory(target.toPath());
                    copy(children(entry.id),target,relative+entry.name+"/",depth+1,budget);
                }else{
                    try(InputStream input=context.getContentResolver().openInputStream(uri(entry.id))){
                        if(input==null)throw new Failure(Reason.COPY_FAILED);
                        copyFile(input,target,entry.size,budget,check);
                    }
                    if(entry.modified>0)target.setLastModified(entry.modified);
                }
            }
        }
    }
    private static final class SourceLock implements AutoCloseable {
        final FileInputStream stream;final FileLock lock;
        SourceLock(FileInputStream stream,FileLock lock){this.stream=stream;this.lock=lock;}
        public void close() throws IOException {if(stream!=null)stream.close();}
    }
    static final class Budget {
        final File volume;final long limit;long bytes,nextSpaceCheck;int entries;
        Budget(File volume) throws Failure {
            this.volume=volume;limit=volume.getUsableSpace()-FREE_HEADROOM;
            if(limit<=0)throw new Failure(Reason.NO_SPACE);
        }
    }
    static void copyFile(InputStream input,File target,long declared,Budget budget,Cancellation check) throws IOException {
        byte[] block=new byte[CHUNK];long copied=0;
        try(FileOutputStream output=new FileOutputStream(target)){
            while(true){
                check.check();int size=input.read(block);if(size<0)break;if(size==0)continue;
                if(size>budget.limit-budget.bytes)throw new Failure(Reason.NO_SPACE);
                if(budget.bytes>=budget.nextSpaceCheck){
                    if(budget.volume.getUsableSpace()<FREE_HEADROOM+size)throw new Failure(Reason.NO_SPACE);
                    budget.nextSpaceCheck=budget.bytes+4L*1024*1024;
                }
                output.write(block,0,size);copied+=size;budget.bytes+=size;
            }
            if(declared>=0&&copied!=declared)throw new Failure(Reason.INCOMPLETE_SOURCE);
            output.getFD().sync();
        }
    }
    static void checkName(String name) throws Failure {
        if(name==null||name.isEmpty()||name.equals(".")||name.equals("..")||name.indexOf('/')>=0||
            name.indexOf('\\')>=0||name.indexOf('\0')>=0)throw new Failure(Reason.INVALID_SOURCE);
    }
    private static void plainDirectory(File directory) throws Failure {
        if(Files.isSymbolicLink(directory.toPath())||!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))
            throw new Failure(Reason.CORRUPT_COPY);
    }
    static String key(String identity){
        try{
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder text=new StringBuilder();for(byte b:digest)text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            return text.toString();
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static void deleteOwnedTree(File root) throws IOException {
        if(Files.isDirectory(root.toPath(),LinkOption.NOFOLLOW_LINKS)){
            File[] children=root.listFiles();
            if(children==null)throw new IOException("Cannot list imported resources");
            for(File child:children)deleteOwnedTree(child);
        }
        Files.delete(root.toPath());
    }
}
