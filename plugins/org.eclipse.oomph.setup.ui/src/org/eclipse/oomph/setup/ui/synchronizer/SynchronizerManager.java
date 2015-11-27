/*
 * Copyright (c) 2015 Eike Stepper (Berlin, Germany) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Eike Stepper - initial API and implementation
 */
package org.eclipse.oomph.setup.ui.synchronizer;

import org.eclipse.oomph.internal.setup.SetupProperties;
import org.eclipse.oomph.internal.ui.UIPropertyTester;
import org.eclipse.oomph.setup.SetupTask;
import org.eclipse.oomph.setup.internal.core.SetupContext;
import org.eclipse.oomph.setup.internal.sync.DataProvider.Location;
import org.eclipse.oomph.setup.internal.sync.DataProvider.NotCurrentException;
import org.eclipse.oomph.setup.internal.sync.LocalDataProvider;
import org.eclipse.oomph.setup.internal.sync.RemoteDataProvider;
import org.eclipse.oomph.setup.internal.sync.SetupSyncPlugin;
import org.eclipse.oomph.setup.internal.sync.Synchronization;
import org.eclipse.oomph.setup.internal.sync.Synchronizer;
import org.eclipse.oomph.setup.internal.sync.SynchronizerAdapter;
import org.eclipse.oomph.setup.internal.sync.SynchronizerJob;
import org.eclipse.oomph.setup.sync.SyncAction;
import org.eclipse.oomph.setup.sync.SyncActionType;
import org.eclipse.oomph.setup.sync.SyncPolicy;
import org.eclipse.oomph.setup.ui.SetupPropertyTester;
import org.eclipse.oomph.setup.ui.SetupUIPlugin;
import org.eclipse.oomph.setup.ui.recorder.AbstractRecorderDialog;
import org.eclipse.oomph.ui.UIUtil;
import org.eclipse.oomph.util.PropertiesUtil;
import org.eclipse.oomph.util.PropertyFile;

import org.eclipse.emf.common.util.EMap;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.userstorage.IStorage;
import org.eclipse.userstorage.IStorageService;
import org.eclipse.userstorage.StorageFactory;
import org.eclipse.userstorage.spi.ICredentialsProvider;
import org.eclipse.userstorage.spi.StorageCache;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Eike Stepper
 */
public final class SynchronizerManager
{
  public static final File SYNC_FOLDER = SetupSyncPlugin.INSTANCE.getUserLocation().toFile();

  public static final SynchronizerManager INSTANCE = new SynchronizerManager();

  public static final boolean ENABLED = PropertiesUtil.isProperty(SetupProperties.PROP_SETUP_SYNC);

  private static final File USER_SETUP = new File(SetupContext.USER_SETUP_LOCATION_URI.toFileString());

  private static final PropertyFile CONFIG = new PropertyFile(SetupSyncPlugin.INSTANCE.getUserLocation().append("sync.properties").toFile());

  private static final String CONFIG_SYNC_ENABLED = "sync.enabled";

  private static final String CONFIG_CONNECTION_OFFERED = "connection.offered";

  /**
   * If set to <code>true</code> the {@link #CONFIG_CONNECTION_OFFERED} property is cleared before each access,
   * which makes it easier to test/debug the opt-in workflow.
   * <p>
   * <b>Should never be committed with a <code>true</code> value!</b>
   */
  private static final boolean DEBUG_CONNECTION_OFFERED = false;

  private final IStorage storage;

  private Boolean connectionOffered;

  private SynchronizerManager()
  {
    StorageCache cache = new RemoteDataProvider.SyncStorageCache(SYNC_FOLDER);
    storage = StorageFactory.DEFAULT.create(RemoteDataProvider.APPLICATION_TOKEN, cache);
  }

  public IStorage getStorage()
  {
    return storage;
  }

  public boolean isSyncEnabled()
  {
    try
    {
      return Boolean.parseBoolean(CONFIG.getProperty(CONFIG_SYNC_ENABLED, "false"));
    }
    catch (Throwable ex)
    {
      SetupUIPlugin.INSTANCE.log(ex);
      return false;
    }
  }

  public boolean setSyncEnabled(boolean enabled)
  {
    boolean changed;

    if (enabled)
    {
      changed = CONFIG.compareAndSetProperty(CONFIG_SYNC_ENABLED, "true", "false", null);
    }
    else
    {
      changed = CONFIG.compareAndSetProperty(CONFIG_SYNC_ENABLED, "false", "true");
    }

    if (changed)
    {
      try
      {
        UIPropertyTester.requestEvaluation(SetupPropertyTester.PREFIX + SetupPropertyTester.SYNC_ENABLED, false);
      }
      catch (Exception ex)
      {
        SetupUIPlugin.INSTANCE.log(ex);
      }
    }

    return changed;
  }

  public Synchronizer createSynchronizer(File userSetup, File syncFolder)
  {
    return createSynchronizer(userSetup, syncFolder, storage);
  }

  public Synchronizer createSynchronizer(File userSetup, File syncFolder, IStorage storage)
  {
    LocalDataProvider localDataProvider = new LocalDataProvider(userSetup);
    RemoteDataProvider remoteDataProvider = new RemoteDataProvider(storage);

    Synchronizer synchronizer = new Synchronizer(localDataProvider, remoteDataProvider, syncFolder);
    synchronizer.addListener(new SkipHandler());

    return synchronizer;
  }

  public SynchronizationController startSynchronization(boolean withCredentialsPrompt, boolean deferLocal)
  {
    SynchronizationController controller = new SynchronizationController();
    if (controller.start(withCredentialsPrompt, deferLocal))
    {
      return controller;
    }

    return null;
  }

  public Synchronization synchronize(boolean withCredentialsPrompt, boolean deferLocal)
  {
    SynchronizationController synchronizationController = startSynchronization(withCredentialsPrompt, deferLocal);
    if (synchronizationController != null)
    {
      return synchronizationController.await();
    }

    return null;
  }

  public void performSynchronization(Synchronization synchronization, boolean interactive, boolean remoteModifications)
  {
    try
    {
      EMap<String, SyncPolicy> policies = synchronization.getRemotePolicies();
      Map<String, SyncAction> actions = synchronization.getActions();
      Map<String, SyncAction> includedActions = new HashMap<String, SyncAction>();

      for (Iterator<Map.Entry<String, SyncAction>> it = actions.entrySet().iterator(); it.hasNext();)
      {
        Map.Entry<String, SyncAction> entry = it.next();
        String syncID = entry.getKey();
        SyncAction syncAction = entry.getValue();

        SyncPolicy policy = policies.get(syncID);
        if (policy == SyncPolicy.EXCLUDE)
        {
          it.remove();
          continue;
        }

        if (policy == null && !interactive)
        {
          it.remove();
          continue;
        }

        SyncActionType type = syncAction.getComputedType();
        switch (type)
        {
          case SET_LOCAL:
          case REMOVE_LOCAL:
            if (!remoteModifications)
            {
              // Ignore LOCAL -> REMOTE actions.
              it.remove();
              continue;
            }
            break;

          case CONFLICT:
            if (!interactive)
            {
              // Ignore interactive actions.
              it.remove();
              continue;
            }
            break;

          case EXCLUDE:
          case NONE:
            // Should not occur.
            it.remove();
            continue;
        }

        if (policy == SyncPolicy.INCLUDE && type != SyncActionType.CONFLICT)
        {
          it.remove();
          includedActions.put(syncID, syncAction);
        }
      }

      if (!actions.isEmpty() || !includedActions.isEmpty())
      {
        if (!actions.isEmpty())
        {
          AbstractRecorderDialog dialog = new SynchronizerDialog(UIUtil.getShell(), null, synchronization);
          if (dialog.open() != AbstractRecorderDialog.OK)
          {
            return;
          }
        }

        actions.putAll(includedActions);

        try
        {
          synchronization.commit();
        }
        catch (NotCurrentException ex)
        {
          SetupUIPlugin.INSTANCE.log(ex, IStatus.INFO);
        }
        catch (IOException ex)
        {
          SetupUIPlugin.INSTANCE.log(ex, IStatus.WARNING);
        }
      }
    }
    finally
    {
      synchronization.dispose();
    }
  }

  public void performFullSynchronization()
  {
    offerFirstTimeConnect(UIUtil.getShell());

    Synchronization synchronization = synchronize(true, false);
    if (synchronization != null)
    {
      performSynchronization(synchronization, true, true);
    }
  }

  public boolean offerFirstTimeConnect(Shell shell)
  {
    if (!ENABLED)
    {
      return false;
    }

    IStorageService service = storage.getService();
    if (service == null)
    {
      return false;
    }

    if (DEBUG_CONNECTION_OFFERED)
    {
      connectionOffered = null;
      CONFIG.removeProperty(CONFIG_CONNECTION_OFFERED);
    }

    if (connectionOffered == null)
    {
      String property = CONFIG.getProperty(CONFIG_CONNECTION_OFFERED, null);
      connectionOffered = property != null;
    }

    if (connectionOffered)
    {
      return false;
    }

    if (!connect(shell))
    {
      return false;
    }

    setSyncEnabled(true);
    return true;
  }

  private boolean connect(final Shell shell)
  {
    try
    {
      final boolean[] result = { false };

      shell.getDisplay().syncExec(new Runnable()
      {
        public void run()
        {
          SynchronizerWelcomeDialog dialog = new SynchronizerWelcomeDialog(shell);
          result[0] = dialog.open() == SynchronizerWelcomeDialog.OK;
        }
      });

      return result[0];
    }
    catch (Throwable ex)
    {
      SetupUIPlugin.INSTANCE.log(ex);
      return false;
    }
    finally
    {
      CONFIG.setProperty(CONFIG_CONNECTION_OFFERED, new Date().toString());
      connectionOffered = true;
    }
  }

  /**
   * @author Eike Stepper
   */
  private static final class SkipHandler extends SynchronizerAdapter
  {
    private static final String CONFIG_SKIPPED_LOCAL = "skipped.local";

    private static final String CONFIG_SKIPPED_REMOTE = "skipped.remote";

    private static final String ID_SEPARATOR = " ";

    private final Set<Location> skippedLocations = new HashSet<Location>();

    private final Set<String> skippedLocal = new HashSet<String>();

    private final Set<String> skippedRemote = new HashSet<String>();

    private final Set<String> computedLocal = new HashSet<String>();

    private final Set<String> computedRemote = new HashSet<String>();

    public SkipHandler()
    {
    }

    @Override
    public void tasksCollected(Synchronization synchronization, Location location, Map<String, SetupTask> oldTasks, Map<String, SetupTask> newTasks)
    {
      Set<String> skippedIDs = getSkippedIDs(location);
      oldTasks.keySet().removeAll(skippedIDs);
    }

    @Override
    public void actionsComputed(Synchronization synchronization, Map<String, SyncAction> actions)
    {
      computedLocal.clear();
      computedRemote.clear();

      analyzeImpact(actions, computedLocal, computedRemote);
    }

    @Override
    public void commitFinished(Synchronization synchronization, Throwable t)
    {
      if (t != null)
      {
        return;
      }

      Set<String> committedLocal = new HashSet<String>();
      Set<String> committedRemote = new HashSet<String>();

      Map<String, SyncAction> actions = synchronization.getActions();
      analyzeImpact(actions, committedLocal, committedRemote);

      computedLocal.removeAll(committedLocal);
      computedRemote.removeAll(committedRemote);

      setSkippedIDs(Location.LOCAL, computedLocal);
      setSkippedIDs(Location.REMOTE, computedRemote);
    }

    private Set<String> getSkippedIDs(Location location)
    {
      Set<String> skippedIDs = location.pick(skippedLocal, skippedRemote);

      if (skippedLocations.add(location))
      {
        String key = location.pick(CONFIG_SKIPPED_LOCAL, CONFIG_SKIPPED_REMOTE);
        String property = CONFIG.getProperty(key, null);
        if (property != null)
        {
          StringTokenizer tokenizer = new StringTokenizer(property, ID_SEPARATOR);
          while (tokenizer.hasMoreTokens())
          {
            String id = tokenizer.nextToken();
            skippedIDs.add(id);
          }
        }
      }

      return skippedIDs;
    }

    private void setSkippedIDs(Location location, Set<String> skippedIDs)
    {
      String key = location.pick(CONFIG_SKIPPED_LOCAL, CONFIG_SKIPPED_REMOTE);
      if (skippedIDs.isEmpty())
      {
        CONFIG.removeProperty(key);
      }
      else
      {
        List<String> list = new ArrayList<String>(skippedIDs);
        Collections.sort(list);

        StringBuilder builder = new StringBuilder();
        for (String id : list)
        {
          if (builder.length() != 0)
          {
            builder.append(ID_SEPARATOR);
          }

          builder.append(id);
        }

        CONFIG.setProperty(key, builder.toString());
      }
    }

    private static void analyzeImpact(Map<String, SyncAction> actions, Set<String> local, Set<String> remote)
    {
      for (Map.Entry<String, SyncAction> entry : actions.entrySet())
      {
        String id = entry.getKey();
        SyncAction action = entry.getValue();

        SyncActionType effectiveType = action.getEffectiveType();
        switch (effectiveType)
        {
          case SET_LOCAL:
          case REMOVE_LOCAL:
            local.add(id);
            break;

          case SET_REMOTE:
          case REMOVE_REMOTE:
            remote.add(id);
            break;

          case CONFLICT:
            local.add(id);
            remote.add(id);
            break;
        }
      }
    }
  }

  /**
   * @author Eike Stepper
   */
  public static final class SynchronizationController
  {
    private SynchronizerJob synchronizerJob;

    public boolean start(boolean withCredentialsPrompt, boolean deferLocal)
    {
      if (ENABLED)
      {
        if (synchronizerJob == null && INSTANCE.isSyncEnabled())
        {
          IStorageService service = INSTANCE.getStorage().getService();
          if (service == null)
          {
            return false;
          }

          Synchronizer synchronizer = INSTANCE.createSynchronizer(USER_SETUP, SYNC_FOLDER);
          synchronizerJob = new SynchronizerJob(synchronizer, deferLocal);
          synchronizerJob.setService(service);

          if (!withCredentialsPrompt)
          {
            synchronizerJob.setCredentialsProvider(ICredentialsProvider.CANCEL);
          }

          synchronizerJob.schedule();
        }
      }

      return synchronizerJob != null;
    }

    public void stop()
    {
      if (!ENABLED)
      {
        return;
      }

      if (synchronizerJob != null)
      {
        synchronizerJob.cancel();
        synchronizerJob = null;
      }
    }

    public Synchronization await()
    {
      if (!ENABLED)
      {
        return null;
      }

      if (synchronizerJob != null)
      {
        final Synchronization[] result = { synchronizerJob.getSynchronization() };

        if (result[0] == null)
        {
          try
          {
            final AtomicBoolean canceled = new AtomicBoolean();
            final IStorageService service = synchronizerJob.getService();

            UIUtil.syncExec(new Runnable()
            {
              public void run()
              {
                try
                {
                  Shell shell = UIUtil.getShell();
                  ProgressMonitorDialog dialog = new ProgressMonitorDialog(shell);

                  final Semaphore authenticationSemaphore = service.getAuthenticationSemaphore();
                  authenticationSemaphore.acquire();

                  dialog.run(true, true, new IRunnableWithProgress()
                  {
                    public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException
                    {
                      authenticationSemaphore.release();

                      String serviceLabel = service.getServiceLabel();
                      result[0] = await(serviceLabel, monitor);
                    }
                  });
                }
                catch (InvocationTargetException ex)
                {
                  SetupUIPlugin.INSTANCE.log(ex);
                }
                catch (InterruptedException ex)
                {
                  canceled.set(true);
                }
              }
            });

            if (result[0] == null && !canceled.get())
            {
              Throwable exception = synchronizerJob.getException();
              if (exception == null || exception instanceof OperationCanceledException)
              {
                return null;
              }

              throw exception;
            }
          }
          catch (Throwable ex)
          {
            SetupUIPlugin.INSTANCE.log(ex);
          }
          finally
          {
            synchronizerJob = null;
          }
        }

        return result[0];
      }

      return null;
    }

    private Synchronization await(String serviceLabel, IProgressMonitor monitor)
    {
      monitor.beginTask("Requesting data from " + serviceLabel + "...", IProgressMonitor.UNKNOWN);

      try
      {
        return synchronizerJob.awaitSynchronization(monitor);
      }
      finally
      {
        monitor.done();
      }
    }
  }
}
