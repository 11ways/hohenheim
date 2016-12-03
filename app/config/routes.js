Router.add({
	name       : 'Home',
	methods    : 'get',
	paths      : '/',
	handler    : 'Static#home',
	breadcrumb : 'static.home'
});

// Add the dashboard to the menu deck
alchemy.plugins.chimera.menu.set('site', {
	title: 'Sites',
	route: 'chimera@ModelAction',
	parameters: {
		controller: 'editor',
		subject: 'site',
		action: 'index'
	},
	icon: {svg: 'connection'}
});
