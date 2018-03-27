Vue.prototype.$priority = ["Immediate", "High", "Regular"];

const TaskItem = {
	props: ['task', 'priority'],
	template: `<div>
	                {{ task.name }}
	                <span>
	                    {{ priority[task.priority] }}
	                </span>
	            </div>
	           `
}

const TasksList = {
	template: `<div>
                  Time: {{current_task_duration}}
                  <br>
                  <b>Current Task</b>
                  <TaskItem
                          v-for="task in current_task"
                          v-bind:task="task"
                          v-bind:priority="priority">
                  </TaskItem>

                  <b>Other Tasks	Priority</b>
                  <TaskItem
                          v-for="task in tasks"
                          v-bind:task="task"
                          v-bind:priority="priority">
                  </TaskItem></div>`,
	methods: {
		getTasks: function() {
			var vm = this
			axios.get('/basched/unfinishedtasks')
				.then(function(response) {
					vm.tasks = _.values(response.data.tasks)

					var current_task = _.filter(vm.tasks, ['current', true]);
					if (current_task.length) {
						vm.current_task = current_task;
						axios.get('/basched/getRemainingPomodoroTime?taskid=' + current_task[0].id + '&priority=' + current_task[0].priority)
							.then(function(response) {
								vm.current_task_duration = response.data.duration;
							})
							.catch(function(error) {
								console.log('Error! Could not reach the API. ' + error);
							})
					}

				})
				.catch(function(error) {
					console.log('Error! Could not reach the API. ' + error);
				})
		}
	},
	data: function() {
		return {
			tasks: [],
			current_task: [],
			priority: this.$priority,
			current_task_duration: 0
		}
	},
	created: function() {
		this.getTasks()
	},
	components: {
		TaskItem
	}
}

const HomePage = {
	template: `
	    <div>
	       <router-link to="/">Home</router-link>
           <TasksList/>
        </div>
    `,
	components: {
		TasksList
	}

}

const routes = [{
	path: '/',
	component: HomePage
}]

const router = new VueRouter({
	routes // short for `routes: routes`
})

const app = new Vue({
	router
}).$mount('#app')