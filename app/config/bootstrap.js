/**
 *
 * Alchemy: Node.js MVC Framework
 * Copyright 2013-2013
 *
 * Licensed under The MIT License
 * Redistributions of files must retain the above copyright notice.
 *
 * @copyright   Copyright 2013-2013
 * @since       0.0.1
 * @license     MIT License (http://www.opensource.org/licenses/mit-license.php)
 */
alchemy.usePlugin('styleboost');
alchemy.usePlugin('i18n');
alchemy.usePlugin('acl', {baselayout: 'layouts/base', bodylayout: 'layouts/body', mainlayout: ['acl_main', 'admin_main', 'main'], mainblock: 'main', contentblock: 'content'});
alchemy.usePlugin('menu');
alchemy.usePlugin('web-components');
alchemy.usePlugin('chimera', {title: 'Hohenheim'});
