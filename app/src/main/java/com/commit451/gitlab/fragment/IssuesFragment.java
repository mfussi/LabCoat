package com.commit451.gitlab.fragment;

import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import com.commit451.gitlab.GitLabApp;
import com.commit451.gitlab.R;
import com.commit451.gitlab.activity.ProjectActivity;
import com.commit451.gitlab.adapter.IssuesAdapter;
import com.commit451.gitlab.api.GitLabClient;
import com.commit451.gitlab.event.IssueChangedEvent;
import com.commit451.gitlab.event.IssueCreatedEvent;
import com.commit451.gitlab.event.ProjectReloadEvent;
import com.commit451.gitlab.model.api.Issue;
import com.commit451.gitlab.model.api.Project;
import com.commit451.gitlab.util.NavigationManager;
import com.squareup.otto.Subscribe;

import java.util.List;

import butterknife.Bind;
import butterknife.BindString;
import butterknife.ButterKnife;
import butterknife.OnClick;
import retrofit.Callback;
import retrofit.Response;
import retrofit.Retrofit;
import timber.log.Timber;

public class IssuesFragment extends BaseFragment {

    public static IssuesFragment newInstance() {
        return new IssuesFragment();
    }

    @Bind(R.id.swipe_layout) SwipeRefreshLayout mSwipeRefreshLayout;
    @Bind(R.id.list) RecyclerView mIssueListView;
    @Bind(R.id.message_text) TextView mMessageView;
    @Bind(R.id.issue_spinner) Spinner mSpinner;

    private Project mProject;
    private IssuesAdapter mIssuesAdapter;
    private EventReceiver mEventReceiver;

    @BindString(R.string.issue_state_value_default)
    String mState;
    private String[] mStates;

    private final Callback<List<Issue>> mIssuesCallback = new Callback<List<Issue>>() {
        @Override
        public void onResponse(Response<List<Issue>> response, Retrofit retrofit) {
            if (getView() == null) {
                return;
            }

            mSwipeRefreshLayout.setRefreshing(false);

            if (!response.isSuccess()) {
                Timber.e("Issues response was not a success: %d", response.code());
                mMessageView.setVisibility(View.VISIBLE);
                mMessageView.setText(R.string.connection_error_issues);
                mIssuesAdapter.setIssues(null);
                return;
            }

            if (!response.body().isEmpty()) {
                mMessageView.setVisibility(View.GONE);
            } else {
                Timber.d("No issues found");
                mMessageView.setVisibility(View.VISIBLE);
                mMessageView.setText(R.string.no_issues);
            }

            mIssuesAdapter.setIssues(response.body());
        }

        @Override
        public void onFailure(Throwable t) {
            Timber.e(t, null);

            if (getView() == null) {
                return;
            }

            mSwipeRefreshLayout.setRefreshing(false);

            mMessageView.setVisibility(View.VISIBLE);
            mMessageView.setText(R.string.connection_error);
            mIssuesAdapter.setIssues(null);
        }
    };

    private final IssuesAdapter.Listener mIssuesAdapterListener = new IssuesAdapter.Listener() {
        @Override
        public void onIssueClicked(Issue issue) {
            if (mProject != null) {
                NavigationManager.navigateToIssue(getActivity(), mProject, issue);
            } else {
                Snackbar.make(getActivity().getWindow().getDecorView(), getString(R.string.wait_for_project_to_load), Snackbar.LENGTH_SHORT)
                        .show();
            }
        }
    };

    private final AdapterView.OnItemSelectedListener mSpinnerItemSelectedListener = new AdapterView.OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            mState = mStates[position];
            loadData();
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {}
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mStates = getResources().getStringArray(R.array.issue_state_values);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_issues, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ButterKnife.bind(this, view);

        mEventReceiver = new EventReceiver();
        GitLabApp.bus().register(mEventReceiver);

        mIssuesAdapter = new IssuesAdapter(mIssuesAdapterListener);
        mIssueListView.setLayoutManager(new LinearLayoutManager(getActivity()));
        mIssueListView.setAdapter(mIssuesAdapter);

        mSpinner.setAdapter(new ArrayAdapter<>(getActivity(), android.R.layout.simple_list_item_1, android.R.id.text1, getResources().getStringArray(R.array.issue_state_names)));
        mSpinner.setOnItemSelectedListener(mSpinnerItemSelectedListener);

        mSwipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                loadData();
            }
        });

        if (getActivity() instanceof ProjectActivity) {
            mProject = ((ProjectActivity) getActivity()).getProject();
            loadData();
        } else {
            throw new IllegalStateException("Incorrect parent activity");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        ButterKnife.unbind(this);
        GitLabApp.bus().unregister(mEventReceiver);
    }

    @OnClick(R.id.add_issue_button)
    public void onAddIssueClick(View fab) {
        if (mProject != null) {
            NavigationManager.navigateToAddIssue(getActivity(), fab, mProject);
        } else {
            Snackbar.make(getActivity().getWindow().getDecorView(), getString(R.string.wait_for_project_to_load), Snackbar.LENGTH_SHORT)
                    .show();
        }
    }

    public void loadData() {
        if (getView() == null) {
            return;
        }

        if (mProject == null) {
            return;
        }

        mSwipeRefreshLayout.post(new Runnable() {
            @Override
            public void run() {
                if (mSwipeRefreshLayout != null) {
                    mSwipeRefreshLayout.setRefreshing(true);
                }
            }
        });

        GitLabClient.instance().getIssues(mProject.getId(), mState).enqueue(mIssuesCallback);
    }

    private class EventReceiver {
        @Subscribe
        public void onProjectReload(ProjectReloadEvent event) {
            mProject = event.mProject;
            loadData();
        }

        @Subscribe
        public void onIssueCreated(IssueCreatedEvent event) {
            mMessageView.setVisibility(View.GONE);
            mIssuesAdapter.addIssue(event.mIssue);
            mIssueListView.smoothScrollToPosition(0);
        }

        @Subscribe
        public void onIssueChanged(IssueChangedEvent event) {
            mIssuesAdapter.updateIssue(event.mIssue);
        }
    }
}