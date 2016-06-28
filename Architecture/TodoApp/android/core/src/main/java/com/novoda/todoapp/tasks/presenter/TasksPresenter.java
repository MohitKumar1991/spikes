package com.novoda.todoapp.tasks.presenter;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.novoda.data.SyncState;
import com.novoda.data.SyncedData;
import com.novoda.event.DataObserver;
import com.novoda.event.Event;
import com.novoda.event.EventObserver;
import com.novoda.todoapp.navigation.Navigator;
import com.novoda.todoapp.task.data.model.Task;
import com.novoda.todoapp.tasks.data.model.Tasks;
import com.novoda.todoapp.tasks.displayer.TasksActionListener;
import com.novoda.todoapp.tasks.displayer.TasksDisplayer;
import com.novoda.todoapp.tasks.loading.displayer.RetryActionListener;
import com.novoda.todoapp.tasks.loading.displayer.TasksLoadingDisplayer;
import com.novoda.todoapp.tasks.service.TasksService;

import rx.Observable;
import rx.functions.Func1;
import rx.subscriptions.CompositeSubscription;

public class TasksPresenter {

    private final TasksService tasksService;
    private final TasksLoadingDisplayer loadingDisplayer;
    private final TasksDisplayer tasksDisplayer;
    private final Navigator navigator;

    private CompositeSubscription subscriptions = new CompositeSubscription();
    private TasksActionListener.Filter currentFilter = TasksActionListener.Filter.ALL;

    public TasksPresenter(TasksService tasksService, TasksDisplayer tasksDisplayer, TasksLoadingDisplayer loadingDisplayer, Navigator navigator) {
        this.tasksService = tasksService;
        this.loadingDisplayer = loadingDisplayer;
        this.tasksDisplayer = tasksDisplayer;
        this.navigator = navigator;
    }

    public void setInitialFilterTo(TasksActionListener.Filter filter) {
        currentFilter = filter;
    }

    public TasksActionListener.Filter getCurrentFilter() {
        return currentFilter;
    }

    public void startPresenting() {
        loadingDisplayer.attach(retryActionListener);
        tasksDisplayer.attach(tasksActionListener);
        subscribeToSourcesFilteredWith(currentFilter);
    }

    private void subscribeToSourcesFilteredWith(TasksActionListener.Filter filter) {
        subscriptions.add(
                getTasksObservableFor(filter)
                        .map(removeDeletedTasks())
                        .filter(new Func1<Tasks, Boolean>() {
                            @Override
                            public Boolean call(Tasks tasks) {
                                return !tasks.isEmpty();
                            }
                        })
                        .subscribe(tasksObserver)
        );
        subscriptions.add(
                getTasksEventObservableFor(filter)
                        .subscribe(tasksEventObserver)
        );
    }

    private Observable<Tasks> getTasksObservableFor(TasksActionListener.Filter filter) {
        switch (filter) {
            case ACTIVE:
                return tasksService.getActiveTasks();
            case COMPLETED:
                return tasksService.getCompletedTasks();
            case ALL:
                return tasksService.getTasks();
            default:
                throw new IllegalArgumentException("Unknown filter type " + filter);
        }
    }

    private Observable<Event<Tasks>> getTasksEventObservableFor(TasksActionListener.Filter filter) {
        switch (filter) {
            case ACTIVE:
                return tasksService.getActiveTasksEvents();
            case COMPLETED:
                return tasksService.getCompletedTasksEvents();
            case ALL:
                return tasksService.getTasksEvents();
            default:
                throw new IllegalArgumentException("Unknown filter type " + filter);
        }
    }

    private Func1<Tasks, Tasks> removeDeletedTasks() {
        return new Func1<Tasks, Tasks>() {
            @Override
            public Tasks call(Tasks tasks) {
                Iterable<SyncedData<Task>> nonDeletedTasks = Iterables.filter(tasks.all(), shouldDisplayTask());
                return Tasks.from(ImmutableList.copyOf(nonDeletedTasks));
            }
        };
    }

    private static Predicate<SyncedData<Task>> shouldDisplayTask() {
        return new Predicate<SyncedData<Task>>() {
            @Override
            public boolean apply(SyncedData<Task> input) {
                return input.syncState() != SyncState.DELETED_LOCALLY;
            }
        };
    }

    public void stopPresenting() {
        loadingDisplayer.detach(retryActionListener);
        tasksDisplayer.detach(tasksActionListener);
        clearSubscriptions();
    }

    private void clearSubscriptions() {
        subscriptions.clear();
        subscriptions = new CompositeSubscription();
    }

    final RetryActionListener retryActionListener = new RetryActionListener() {
        @Override
        public void onRetry() {
            tasksService.refreshTasks().call();
        }
    };

    final TasksActionListener tasksActionListener = new TasksActionListener() {
        @Override
        public void onTaskSelected(Task task) {
            navigator.toTaskDetail(task);
        }

        @Override
        public void toggleCompletion(Task task) {
            if (task.isCompleted()) {
                tasksService.activate(task).call();
            } else {
                tasksService.complete(task).call();
            }
        }

        @Override
        public void onRefreshSelected() {
            tasksService.refreshTasks().call();
        }

        @Override
        public void onClearCompletedSelected() {
            tasksService.clearCompletedTasks().call();
        }

        @Override
        public void onFilterSelected(Filter filter) {
            currentFilter = filter;
            clearSubscriptions();
            subscribeToSourcesFilteredWith(filter);
        }

        @Override
        public void onAddTaskSelected() {
            navigator.toAddTask();
        }
    };

    private final DataObserver<Tasks> tasksObserver = new DataObserver<Tasks>() {
        @Override
        public void onNext(Tasks tasks) {
            tasksDisplayer.display(tasks);
        }
    };

    private final EventObserver<Tasks> tasksEventObserver = new EventObserver<Tasks>() {
        @Override
        public void onLoading(Event<Tasks> event) {
            if (event.data().isPresent()) {
                loadingDisplayer.showLoadingIndicator();
            } else {
                loadingDisplayer.showLoadingScreen();
            }
        }

        @Override
        public void onIdle(Event<Tasks> event) {
            if (event.data().isPresent()) {
                loadingDisplayer.showData();
            } else {
                showEmptyScreen();
            }
        }

        @Override
        public void onError(Event<Tasks> event) {
            if (event.data().isPresent()) {
                loadingDisplayer.showErrorIndicator();
            } else {
                loadingDisplayer.showErrorScreen();
            }
        }
    };

    private void showEmptyScreen() {
        switch (currentFilter) {
            case ALL:
                loadingDisplayer.showEmptyTasksScreen();
                break;
            case ACTIVE:
                loadingDisplayer.showEmptyActiveTasksScreen();
                break;
            case COMPLETED:
                loadingDisplayer.showEmptyCompletedTasksScreen();
                break;
        }
    }

}
