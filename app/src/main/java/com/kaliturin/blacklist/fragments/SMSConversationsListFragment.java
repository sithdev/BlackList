package com.kaliturin.blacklist.fragments;


import android.content.Context;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.kaliturin.blacklist.InternalEventBroadcast;
import com.kaliturin.blacklist.R;
import com.kaliturin.blacklist.activities.CustomFragmentActivity;
import com.kaliturin.blacklist.adapters.SMSConversationsListCursorAdapter;
import com.kaliturin.blacklist.utils.ContactsAccessHelper;
import com.kaliturin.blacklist.utils.ContactsAccessHelper.SMSConversation;
import com.kaliturin.blacklist.utils.DatabaseAccessHelper;
import com.kaliturin.blacklist.utils.DatabaseAccessHelper.Contact;
import com.kaliturin.blacklist.utils.DefaultSMSAppHelper;
import com.kaliturin.blacklist.utils.DialogBuilder;
import com.kaliturin.blacklist.utils.Permissions;
import com.kaliturin.blacklist.utils.ProgressDialogHolder;
import com.kaliturin.blacklist.utils.Utils;


/**
 * Fragment for showing all SMS conversations
 */
public class SMSConversationsListFragment extends Fragment implements FragmentArguments {
    private static final String LIST_POSITION = "LIST_POSITION";

    private InternalEventBroadcast internalEventBroadcast = null;
    private SMSConversationsListCursorAdapter cursorAdapter = null;
    private ListView listView = null;
    private int listPosition = 0;

    public SMSConversationsListFragment() {
        // Required empty public constructor
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        // set activity title
        Bundle arguments = getArguments();
        ActionBar actionBar = ((AppCompatActivity) getActivity()).getSupportActionBar();
        if (arguments != null && actionBar != null) {
            actionBar.setTitle(arguments.getString(TITLE));
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            listPosition = savedInstanceState.getInt(LIST_POSITION, 0);
        }

        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_sms_conversations_list, container, false);
    }

    @Override
    public void onViewCreated(final View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // notify user if permission isn't granted
        Permissions.notifyIfNotGranted(getContext(), Permissions.READ_SMS);
        Permissions.notifyIfNotGranted(getContext(), Permissions.READ_CONTACTS);

        // cursor adapter
        cursorAdapter = new SMSConversationsListCursorAdapter(getContext());
        // on row click listener (receives clicked row)
        cursorAdapter.setOnClickListener(new OnRowClickListener());
        // on row long click listener (receives clicked row)
        cursorAdapter.setOnLongClickListener(new OnRowLongClickListener());

        // add cursor listener to the list
        listView = (ListView) view.findViewById(R.id.rows_list);
        listView.setAdapter(cursorAdapter);

        // on list empty comment
        TextView textEmptyView = (TextView) view.findViewById(R.id.text_empty);
        listView.setEmptyView(textEmptyView);

        // init internal broadcast event receiver
        internalEventBroadcast = new InternalEventBroadcast() {
            @Override
            public void onSMSWasWritten(@NonNull String phoneNumber) {
                // SMS thread was read -
                ContactsAccessHelper db = ContactsAccessHelper.getInstance(getContext());
                int threadId = db.getSMSThreadIdByNumber(getContext(), phoneNumber);
                if (threadId >= 0 &&
                        // refresh cached list view items
                        cursorAdapter.invalidateCache(threadId)) {
                    cursorAdapter.notifyDataSetChanged();
                } else {
                    // reload all list view items
                    loadListViewItems();
                }
            }

            @Override
            public void onSMSWasRead(int threadId) {
                // SMS thread from the Inbox was read - refresh cached list view items
                cursorAdapter.invalidateCache(threadId);
                cursorAdapter.notifyDataSetChanged();
            }
        };
        internalEventBroadcast.register(getContext());

        // load SMS conversations to the list
        loadListViewItems(listPosition);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(LIST_POSITION, listView.getFirstVisiblePosition());
    }

    @Override
    public void onDestroyView() {
        getLoaderManager().destroyLoader(0);
        internalEventBroadcast.unregister(getContext());
        super.onDestroyView();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.main, menu);

        MenuItem writeSMS = menu.findItem(R.id.write_message);
        Utils.setMenuIconTint(getContext(), writeSMS, R.attr.colorAccent);
        writeSMS.setVisible(true);

        writeSMS.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                // open SMS sending activity
                CustomFragmentActivity.show(getContext(),
                        getString(R.string.New_message),
                        SMSSendFragment.class, null);
                return true;
            }
        });

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public void onPause() {
        super.onPause();
        listPosition = listView.getFirstVisiblePosition();
    }

//----------------------------------------------------------------------

    // On row click listener
    private class OnRowClickListener implements View.OnClickListener {
        @Override
        public void onClick(final View row) {
            // get the clicked conversation
            final SMSConversation sms = cursorAdapter.getSMSConversation(row);
            if (sms != null) {
                // open activity with all the SMS of the conversation
                Bundle arguments = new Bundle();
                arguments.putInt(THREAD_ID, sms.threadId);
                arguments.putInt(UNREAD_COUNT, sms.unread);
                arguments.putString(CONTACT_NUMBER, sms.number);
                String title = (sms.person != null ? sms.person : sms.number);
                CustomFragmentActivity.show(getContext(), title,
                        SMSConversationFragment.class, arguments);
            }
        }
    }

    // On row long click listener
    private class OnRowLongClickListener implements View.OnLongClickListener {
        @Override
        public boolean onLongClick(View row) {
            final SMSConversation sms = cursorAdapter.getSMSConversation(row);
            if (sms == null) {
                return true;
            }

            final String person = (sms.person != null ? sms.person : sms.number);

            // create menu dialog
            DialogBuilder dialog = new DialogBuilder(getContext());
            dialog.setTitle(person);
            // add menu item of sms deletion
            dialog.addItem(R.string.Delete_thread, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (DefaultSMSAppHelper.isDefault(getContext())) {
                        // remove SMS thread
                        ContactsAccessHelper db = ContactsAccessHelper.getInstance(getContext());
                        if (db.deleteSMSMessagesByThreadId(getContext(), sms.threadId)) {
                            // reload list
                            loadListViewItems();
                        }
                    } else {
                        Toast.makeText(getContext(), R.string.Need_default_SMS_app,
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });

            final DatabaseAccessHelper db = DatabaseAccessHelper.getInstance(getContext());
            if (db != null) {
                // 'move contact to black list'
                DatabaseAccessHelper.Contact contact = db.getContact(person, sms.number);
                if (contact == null || contact.type != Contact.TYPE_BLACK_LIST) {
                    dialog.addItem(R.string.Move_to_black_list, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            db.addContact(Contact.TYPE_BLACK_LIST, person, sms.number);
                        }
                    });
                }

                // 'move contact to white list'
                if (contact == null || contact.type != Contact.TYPE_WHITE_LIST) {
                    dialog.addItem(R.string.Move_to_white_list, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            db.addContact(Contact.TYPE_WHITE_LIST, person, sms.number);
                        }
                    });
                }
            }

            dialog.show();

            return true;
        }
    }

//----------------------------------------------------------------------

    // Loads SMS conversations to the list view
    private void loadListViewItems() {
        int listPosition = listView.getFirstVisiblePosition();
        loadListViewItems(listPosition);
    }

    // Loads SMS conversations to the list view
    private void loadListViewItems(int listPosition) {
        if (!isAdded()) {
            return;
        }
        int loaderId = 0;
        ConversationsLoaderCallbacks callbacks =
                new ConversationsLoaderCallbacks(getContext(),
                        listView, listPosition, cursorAdapter);

        LoaderManager manager = getLoaderManager();
        if (manager.getLoader(loaderId) == null) {
            // init and run the items loader
            manager.initLoader(loaderId, null, callbacks);
        } else {
            // restart loader
            manager.restartLoader(loaderId, null, callbacks);
        }
    }

    // SMS conversations loader
    private static class ConversationsLoader extends CursorLoader {
        ConversationsLoader(Context context) {
            super(context);
        }

        @Override
        public Cursor loadInBackground() {
            // get all SMS conversations
            ContactsAccessHelper db = ContactsAccessHelper.getInstance(getContext());
            return db.getSMSConversations(getContext());
        }
    }

    // SMS conversations loader callbacks
    private static class ConversationsLoaderCallbacks implements LoaderManager.LoaderCallbacks<Cursor> {
        private ProgressDialogHolder progress = new ProgressDialogHolder();
        private SMSConversationsListCursorAdapter cursorAdapter;
        private Context context;
        private ListView listView;
        private int listPosition;

        ConversationsLoaderCallbacks(Context context, ListView listView, int listPosition,
                                     SMSConversationsListCursorAdapter cursorAdapter) {
            this.context = context;
            this.listView = listView;
            this.listPosition = listPosition;
            this.cursorAdapter = cursorAdapter;
        }

        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            progress.show(context, R.string.Loading_);
            return new ConversationsLoader(context);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
            cursorAdapter.changeCursor(cursor);

            if (!cursorAdapter.isEmpty()) {
                // scroll list to saved position
                listView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cursorAdapter.getCount() > 0) {
                            listView.setSelection(listPosition);
                            listView.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }

            if (cursor != null) {
                // mark all SMS are seen
                new SMSSeenMarker(context).execute();
            }

            progress.dismiss();
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
            cursorAdapter.changeCursor(null);
            progress.dismiss();
        }
    }

//----------------------------------------------------------------------

    // Async task - marks all SMS are seen
    private static class SMSSeenMarker extends AsyncTask<Void, Void, Void> {
        private Context context;

        SMSSeenMarker(Context context) {
            this.context = context;
        }

        @Override
        protected Void doInBackground(Void... params) {
            ContactsAccessHelper db = ContactsAccessHelper.getInstance(context);
            db.setSMSMessagesSeen(context);
            return null;
        }
    }
}