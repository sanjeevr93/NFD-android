/* -*- Mode:jde; c-file-style:"gnu"; indent-tabs-mode:nil; -*- */
/*
 * Copyright (c) 2015-2019 Regents of the University of California
 * <p/>
 * This file is part of NFD (Named Data Networking Forwarding Daemon) Android.
 * See AUTHORS.md for complete list of NFD Android authors and contributors.
 * <p/>
 * NFD Android is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 * <p/>
 * NFD Android is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PURPOSE.  See the GNU General Public License for more details.
 * <p/>
 * You should have received a copy of the GNU General Public License along with
 * NFD Android, e.g., in COPYING.md file.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.named_data.nfd;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.v4.app.ListFragment;
import android.support.v4.content.ContextCompat;
import android.util.Pair;
import android.util.SparseArray;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.intel.jndn.management.ManagementException;
import com.intel.jndn.management.types.FaceStatus;

import net.named_data.jndn.Name;
import net.named_data.jndn_xx.util.FaceUri;
import net.named_data.nfd.utils.G;
import net.named_data.nfd.utils.NfdcHelper;
import net.named_data.nfd.utils.SharedPreferencesManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FaceListFragment extends ListFragment implements
    FaceCreateDialogFragment.OnFaceCreateRequested,
    FaceListRouteCreateDialogFragment.OnFaceListRouteCreateRequestd {

  public static FaceListFragment
  newInstance() {
    return new FaceListFragment();
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setHasOptionsMenu(true);
  }

  @Override
  public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
    super.onCreateContextMenu(menu, v, menuInfo);
    MenuInflater inflater = getActivity().getMenuInflater();
    inflater.inflate(R.menu.menu_face_list_floating, menu);
  }

  @Override
  public boolean onContextItemSelected(MenuItem item) {
    HashSet<Integer> m_facesToDelete = new HashSet<>();
    AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
    if (info.id < 256) {
      return super.onContextItemSelected(item);
    }

    FaceStatus faceStatus = m_faceListAdapter.getItem(info.position - 1);
    switch (item.getItemId()) {

      case R.id.menu_item_add_route_face_item_floating:
        G.Log("Requesting to add route using face: " + faceStatus.getRemoteUri());

        FaceListRouteCreateDialogFragment dialog =
            FaceListRouteCreateDialogFragment.newInstance((int) info.id);
        dialog.setTargetFragment(FaceListFragment.this, 0);
        dialog.show(getFragmentManager(), "FaceListRouteCreateFragment");
        return true;

      case R.id.menu_item_delete_face_item_floating:
        G.Log("Requesting to delete face: " + faceStatus.getRemoteUri());

        new AlertDialog.Builder(getContext())
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(R.string.face_list_delete_face_dialog_title)
            .setMessage(getString(R.string.face_list_delete_face_dialog_warning) + "\n" +
                        faceStatus.getRemoteUri() + " ?")
            .setPositiveButton(R.string.menu_item_delete_face_item, new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialogInterface, int i) {
                m_facesToDelete.add((int) info.id);
                m_faceDestroyAsyncTask = new FaceDestroyAsyncTask();
                m_faceDestroyAsyncTask.execute(m_facesToDelete);
              }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        return true;

      case R.id.menu_item_details_face_item_floating:
        G.Log("Requesting to check details of face: " + faceStatus.getRemoteUri());

        if (m_callbacks != null) {
          m_callbacks.onFaceItemSelected(faceStatus);
        }
        return true;

      default:
        return super.onContextItemSelected(item);
    }
  }

  @Override
  public void onViewCreated(View view, Bundle savedInstanceState)
  {
    super.onViewCreated(view, savedInstanceState);

    View v = getLayoutInflater().inflate(R.layout.fragment_face_list_list_header, getListView(), false);
    getListView().addHeaderView(v, null, false);
    getListView().setDivider(ContextCompat.getDrawable(getContext(), R.drawable.list_item_divider));

    // Get info unavailable view
    m_faceListInfoUnavailableView = v.findViewById(R.id.face_list_info_unavailable);

    // Get progress bar spinner view
    m_reloadingListProgressBar = v.findViewById(R.id.face_list_reloading_list_progress_bar);

    // Setup list view for deletion
    ListView listView = getListView();
    registerForContextMenu(listView);
  }

  @Override
  public void onActivityCreated(@Nullable Bundle savedInstanceState)
  {
    super.onActivityCreated(savedInstanceState);

    if (m_faceListAdapter == null) {
      m_faceListAdapter = new FaceListAdapter(getActivity());
    }
    // setListAdapter must be called after addHeaderView.  Otherwise, there is an exception on some platforms.
    // http://stackoverflow.com/a/8141537/2150331
    setListAdapter(m_faceListAdapter);
  }

  @Override
  public void onDestroyView()
  {
    super.onDestroyView();
    setListAdapter(null);
  }

  @Override
  public void onCreateOptionsMenu(Menu menu, MenuInflater inflater)
  {
    super.onCreateOptionsMenu(menu, inflater);
    inflater.inflate(R.menu.menu_face_list, menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item)
  {
    switch (item.getItemId()) {
      case R.id.face_list_refresh:
        retrieveFaceList();
        return true;
      case R.id.face_list_add:
        FaceCreateDialogFragment dialog = FaceCreateDialogFragment.newInstance();
        dialog.setTargetFragment(FaceListFragment.this, 0);
        dialog.show(getFragmentManager(), "FaceCreateFragment");
        return true;
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  public void onResume() {
    super.onResume();
    startFaceListRetrievalTask();
  }

  @Override
  public void onPause() {
    super.onPause();
    stopFaceListRetrievalTask();

    if (m_faceDestroyAsyncTask != null) {
      m_faceDestroyAsyncTask.cancel(false);
      m_faceDestroyAsyncTask = null;
    }

    if (m_faceCreateAsyncTask != null) {
      m_faceCreateAsyncTask.cancel(false);
      m_faceCreateAsyncTask = null;
    }
  }

  @SuppressWarnings("deprecation")
  @Override
  public void onAttach(Activity activity) {
    super.onAttach(activity);
    try {
      m_callbacks = (Callbacks)activity;
    } catch (Exception e) {
      G.Log("Hosting activity must implement this fragment's callbacks: " + e);
    }
  }

  @Override
  public void onDetach() {
    super.onDetach();
    m_callbacks = null;
  }

  @Override
  public void
  onListItemClick(ListView l, View v, int position, long id) {
    if (m_callbacks != null) {
      FaceStatus faceStatus = (FaceStatus)l.getAdapter().getItem(position);
      m_callbacks.onFaceItemSelected(faceStatus);
    }
  }

  @Override
  public void
  createFace(String faceUri, boolean isPermanent)
  {
    m_faceCreateAsyncTask = new FaceCreateAsyncTask(faceUri, isPermanent);
    m_faceCreateAsyncTask.execute();
  }

  @Override
  public void createRoute(Name prefix, int faceId, boolean isPermanent) {
    m_routeCreateAsyncTask = new RouteCreateAsyncTask(prefix, faceId, "", isPermanent, false);
    m_routeCreateAsyncTask.execute();
  }

  /////////////////////////////////////////////////////////////////////////

  /**
   * Updates the underlying adapter with the given list of FaceStatus.
   *
   * Note: This method should only be called from the UI thread.
   *
   * @param list Update ListView with the given List&lt;FaceStatus&gt;
   */
  private void updateFaceList(List<FaceStatus> list) {
    if (list == null) {
      m_faceListInfoUnavailableView.setVisibility(View.VISIBLE);
      return;
    }

    ((FaceListAdapter)getListAdapter()).updateList(list);
  }

  /**
   * Convenience method that starts the AsyncTask that retrieves the
   * list of available faces.
   */
  private void retrieveFaceList() {
    // Update UI
    m_faceListInfoUnavailableView.setVisibility(View.GONE);

    // Stop if running; before starting the new Task
    stopFaceListRetrievalTask();
    startFaceListRetrievalTask();
  }

  /**
   * Create a new AsyncTask for face list information retrieval.
   */
  private void startFaceListRetrievalTask() {
    m_faceListAsyncTask = new FaceListAsyncTask();
    m_faceListAsyncTask.execute();
  }

  /**
   * Stops a previously started face retrieval AsyncTask.
   */
  private void stopFaceListRetrievalTask() {
    if (m_faceListAsyncTask != null) {
      m_faceListAsyncTask.cancel(false);
      m_faceListAsyncTask = null;
    }
  }

  /**
   * Custom adapter for displaying face information in a ListView.
   */
  private static class FaceListAdapter extends BaseAdapter {
    private FaceListAdapter(Context context) {
      m_layoutInflater = LayoutInflater.from(context);
    }

    private void
    updateList(List<FaceStatus> faces) {
      m_faces = faces;
      notifyDataSetChanged();
    }

    @Override
    public FaceStatus
    getItem(int i)
    {
      assert m_faces != null;
      return m_faces.get(i);
    }

    @Override
    public long getItemId(int i)
    {
      assert m_faces != null;
      return m_faces.get(i).getFaceId();
    }

    @Override
    public int getCount()
    {
      return (m_faces != null) ? m_faces.size() : 0;
    }

    @SuppressLint("InflateParams")
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      FaceInfoHolder holder;

      if (convertView == null) {
        holder = new FaceInfoHolder();

        convertView = m_layoutInflater.inflate(R.layout.list_item_face_status_item, null);
        convertView.setTag(holder);

        holder.m_faceUri = (TextView)convertView.findViewById(R.id.list_item_face_uri);
        holder.m_faceId = (TextView)convertView.findViewById(R.id.list_item_face_id);
      } else {
        holder = (FaceInfoHolder)convertView.getTag();
      }

      FaceStatus info = getItem(position);
      holder.m_faceUri.setText(info.getRemoteUri());
      holder.m_faceId.setText(String.valueOf(info.getFaceId()));

      return convertView;
    }

    private static class FaceInfoHolder {
      private TextView m_faceUri;
      private TextView m_faceId;
    }

    private final LayoutInflater m_layoutInflater;
    private List<FaceStatus> m_faces;
  }

  /**
   * AsyncTask that gets the list of faces from the running NFD.
   */
  private class FaceListAsyncTask extends AsyncTask<Void, Void, Pair<List<FaceStatus>, Exception>> {
    @Override
    protected void
    onPreExecute() {
      // Display progress bar
      m_reloadingListProgressBar.setVisibility(View.VISIBLE);
    }

    @Override
    protected Pair<List<FaceStatus>, Exception>
    doInBackground(Void... params) {
      Exception returnException = null;
      NfdcHelper nfdcHelper = new NfdcHelper();
      List<FaceStatus> faceStatusList = null;
      try {
        faceStatusList = nfdcHelper.faceList(getActivity().getApplicationContext());
      } catch (Exception e) {
        returnException = e;
      }
      nfdcHelper.shutdown();
      return new Pair<>(faceStatusList, returnException);
    }

    @Override
    protected void
    onCancelled() {
      // Remove progress bar
      m_reloadingListProgressBar.setVisibility(View.GONE);

      // Nothing to do here; No change in UI.
    }

    @Override
    protected void
    onPostExecute(Pair<List<FaceStatus>, Exception> result) {
      // Remove progress bar
      m_reloadingListProgressBar.setVisibility(View.GONE);

      if (result.second != null) {
        Toast.makeText(getActivity(), "Error communicating with NFD (" + result.second.getMessage() + ")",
                       Toast.LENGTH_LONG).show();
      }

      updateFaceList(result.first);
    }
  }

  /**
   * AsyncTask that destroys faces that are passed in as a list of FaceInfo.
   */
  private class FaceDestroyAsyncTask extends AsyncTask<Set<Integer>, Void, Exception> {
    @Override
    protected void
    onPreExecute() {
      // Display progress bar
      m_reloadingListProgressBar.setVisibility(View.VISIBLE);
    }

    @SafeVarargs
    @Override
    protected final Exception
    doInBackground(Set<Integer>... params) {
      Exception retval = null;

      NfdcHelper nfdcHelper = new NfdcHelper();
      try {
        Context context = getActivity().getApplicationContext();
        SparseArray<FaceStatus> faceSparseArray = nfdcHelper.faceListAsSparseArray(context);
        for (Set<Integer> faces : params) {
          for (int faceId : faces) {
            FaceStatus one = faceSparseArray.get(faceId, null);
            if (null != one){
              removeRoutesOfFace(nfdcHelper, faceId);
              // TODO: what if face was saved but is not in the face list? Is it possible?
              SharedPreferencesManager.deletePermanentFaceUri(
                  context,
                  one.getRemoteUri()
              );
              SharedPreferencesManager.deletePermanentFaceId(context, faceId);
              nfdcHelper.faceDestroy(faceId);
            }
          }
        }
      } catch (Exception e) {
        retval = e;
      }
      nfdcHelper.shutdown();
      return retval;
    }

    @Override
    protected void onCancelled() {
      // Remove progress bar
      m_reloadingListProgressBar.setVisibility(View.GONE);

      // Nothing to do here; No change in UI.
    }

    @Override
    protected void onPostExecute(Exception e) {
      // Remove progress bar
      m_reloadingListProgressBar.setVisibility(View.GONE);

      if (e != null) {
        Toast.makeText(getActivity(), "Error communicating with NFD (" + e.getMessage() + ")",
                       Toast.LENGTH_LONG).show();
      }
      else {
        // Reload face list
        m_timeoutHandler.postDelayed(new Runnable() {
          @Override
          public void run() {
            retrieveFaceList();
          }
        }, 500);
      }
    }
  }

  private void
  removeRoutesOfFace(NfdcHelper nfdcHelper, int faceId) throws ManagementException {
    SparseArray<Set<Name>> faceIdPrefixSparseArray = nfdcHelper.ribAsFaceIdPrefixNameArray();
    Set<Name> prefixes = faceIdPrefixSparseArray.get(faceId, null);
    if (null != prefixes){
      RouteListFragment.removeRouteSyncs(getActivity().getApplicationContext(), faceId, prefixes);
    }
  }

  private class FaceCreateAsyncTask extends AsyncTask<Void, Void, String> {
    public FaceCreateAsyncTask(String faceUri, boolean isPermanent)
    {
      m_faceUri = faceUri;
      m_isPermanent = isPermanent;
    }

    @Override
    protected String
    doInBackground(Void... params)
    {
      try {
        NfdcHelper nfdcHelper = new NfdcHelper();
        Context context = getActivity().getApplicationContext();
        int faceId = nfdcHelper.faceCreate(m_faceUri);
        if (m_isPermanent) {
          SharedPreferencesManager.addPermanentFaceUri(
                                             context,
                                             NfdcHelper.formatFaceUri(m_faceUri)
                                         );
          SharedPreferencesManager.addPermanentFaceId(context, faceId);
        }
        nfdcHelper.shutdown();
        return "OK. Face id: " + String.valueOf(faceId);
      }
      catch (FaceUri.CanonizeError e) {
        return "Error creating face (" + e.getMessage() + ")";
      }
      catch (FaceUri.Error e) {
        return "Error creating face (" + e.getMessage() + ")";
      }
      catch (Exception e) {
        return "Error communicating with NFD (" + e.getMessage() + ")";
      }
    }

    @Override
    protected void onPreExecute()
    {
      // Display progress bar
      m_reloadingListProgressBar.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onPostExecute(String status)
    {
      // Display progress bar
      m_reloadingListProgressBar.setVisibility(View.VISIBLE);
      Toast.makeText(getActivity(), status, Toast.LENGTH_LONG).show();

      retrieveFaceList();
    }

    @Override
    protected void onCancelled()
    {
      // Remove progress bar
      m_reloadingListProgressBar.setVisibility(View.GONE);
    }

    ///////////////////////////////////////////////////////////////////////////

    private String m_faceUri;
    private boolean m_isPermanent;
  }

  private class RouteCreateAsyncTask extends AsyncTask<Void, Void, String> {
    RouteCreateAsyncTask(Name prefix, int faceId, String faceUri, boolean isPermanent, boolean isFaceUri) {
      m_prefix = prefix;
      m_faceId = faceId;
      m_faceUri = faceUri;
      m_isPermanent = isPermanent;
      m_isFaceUri = isFaceUri;
    }

    @Override
    protected String
    doInBackground(Void... params) {
      NfdcHelper nfdcHelper = new NfdcHelper();
      try {
        if (m_isFaceUri) {
          int faceId = nfdcHelper.faceCreate(m_faceUri);
          nfdcHelper.ribRegisterPrefix(new Name(m_prefix), faceId, 10, true, false);
          if (m_isPermanent) {
            Context context = getActivity().getApplicationContext();
            SharedPreferencesManager
                .addPermanentRoute(
                    context,
                    m_prefix.toUri(),
                    NfdcHelper.formatFaceUri(m_faceUri)
                );
          }
        } else {
          nfdcHelper.ribRegisterPrefix(new Name(m_prefix), m_faceId, 10, true, false);
          if (m_isPermanent) {
            String uri = null;
            for (FaceStatus faceStatus : nfdcHelper.faceList()) {
              if (faceStatus.getFaceId() == m_faceId) {
                uri = faceStatus.getRemoteUri();
                System.out.println(">>>>>>>>> " + uri);
                break;
              }
            }
            if (uri == null) {
              throw new ManagementException("Face not found: " + uri);
            }
            Context context = getActivity().getApplicationContext();
            SharedPreferencesManager
                .addPermanentRoute(
                    context,
                    m_prefix.toUri(),
                    NfdcHelper.formatFaceUri(uri)
                );
          }
        }
        nfdcHelper.shutdown();
        return "OK";
      } catch (FaceUri.CanonizeError|FaceUri.Error e) {
        return "Error creating face (" + e.getMessage() + ")";
      } catch (Exception e) {
        return "Error communicating with NFD (" + e.getMessage() + ")";
      } finally {
        nfdcHelper.shutdown();
      }
    }

    @Override
    protected void
    onPreExecute() {
      // Display progress bar
      m_reloadingListProgressBar.setVisibility(View.VISIBLE);
    }

    @Override
    protected void
    onPostExecute(String status) {
      // Display progress bar
      m_reloadingListProgressBar.setVisibility(View.VISIBLE);
      Toast.makeText(getActivity(), status, Toast.LENGTH_LONG).show();

      retrieveFaceList();
    }

    @Override
    protected void
    onCancelled() {
      // Remove progress bar
      m_reloadingListProgressBar.setVisibility(View.GONE);
    }

    ///////////////////////////////////////////////////////////////////////////

    private Name m_prefix;
    private String m_faceUri;
    private int m_faceId;
    private boolean m_isPermanent;
    private boolean m_isFaceUri;
  }
  /////////////////////////////////////////////////////////////////////////

  public interface Callbacks {
    /**
     * This method is called when a face is selected and more
     * information about the face should be presented to the user.
     *
     * @param faceStatus FaceStatus instance with information about the face
     */
    public void onFaceItemSelected(FaceStatus faceStatus);
  }

  /////////////////////////////////////////////////////////////////////////

  /** Reference to the most recent AsyncTask that was created for listing faces */
  private FaceListAsyncTask m_faceListAsyncTask;

  /** Callback handler of the hosting activity */
  private Callbacks m_callbacks;

  /** Reference to the most recent AsyncTask that was created for destroying faces */
  private FaceDestroyAsyncTask m_faceDestroyAsyncTask;

  /** Reference to the most recent AsyncTask that was created for creating a face */
  private FaceCreateAsyncTask m_faceCreateAsyncTask;

  /** Reference to the view to be displayed when no information is available */
  private View m_faceListInfoUnavailableView;

  /** Progress bar spinner to display to user when destroying faces */
  private ProgressBar m_reloadingListProgressBar;

  private FaceListAdapter m_faceListAdapter;

  private Handler m_timeoutHandler = new Handler();

  private RouteCreateAsyncTask m_routeCreateAsyncTask;

}
