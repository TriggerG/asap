
// get all gradients
let nodes = document.querySelectorAll('[gradient]');
let dict = {};
// fill dict
Array.from( document.querySelectorAll('[gradient]') ).forEach( e => {
    const key = e.getAttribute( 'gradient' );
    if( dict[key] != undefined ) {
        const list = Array.from( dict[key] );
        list.push(e);
        dict[key] = list;
    } else
        dict[key] = [e];
} );

// use dict
Object.keys( dict ).forEach( function( key ) {
    let nodes = this[key];
    let values = nodes.map( (e, i, arr) => +e.childNodes[0].textContent.replace( /\./g, '' ).replace( /,/g, '.' ) );
    let max = Math.max(...values);
    for( let i = 0; i < nodes.length; i++ ) {
        let left = values[i] / max * 100; // percents of left
        if( nodes[i].getAttribute( 'zs-warning' ) == '' )
            nodes[i].style.backgroundImage = `-webkit-linear-gradient(left, #FFA5A7 ${left}%, #FFD1D2 ${left}%)`;
        else
            nodes[i].style.backgroundImage = `-webkit-linear-gradient(left, #A2CDF1 ${left}%, transparent ${left}%)`;
        nodes[i].style.borderRight = '1px solid white';
    }
}, dict );