Vue.component('task-item', {
  props: ['task','priority'],
  template: '<div>{{ task.name }}<span> {{ priority[task.priority] }} </span></div>'
})

var watchExampleVM = new Vue({
    el: '#watch-example',
    data: {
        tasks: [],
        priority: ["Immediate", "High", "Regular"],
        current_task: [],
        current_task_duration: 0,
    },
    methods: {
        getTasks: function () {
            var vm = this
            axios.get('/basched/unfinishedtasks')
            .then(function (response) {
                vm.tasks = _.values(response.data.tasks)

                var current_task = _.filter(vm.tasks, ['current', true]);
                if(current_task.length) {
                    vm.current_task = current_task;
                    axios.get('/basched/getRemainingPomodoroTime?taskid='+current_task[0].id+'&priority='+current_task[0].priority)
                    .then(function (response) {
                        vm.current_task_duration = response.data.duration;
                    })
                    .catch(function (error) {
                            console.log('Error! Could not reach the API. ' + error);
                    })
                }

            })
            .catch(function (error) {
                console.log('Error! Could not reach the API. ' + error);
            })
        }
    }
})

watchExampleVM.getTasks();