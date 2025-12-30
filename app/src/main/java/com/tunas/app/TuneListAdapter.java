package com.tunas.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;
import com.tunas.app.TuneFavorites.StarColor;
import java.util.List;

public class TuneListAdapter extends BaseAdapter {
    private final Context context;
    private final List<String> tunes;
    private final TuneFavorites favorites;

    public TuneListAdapter(Context context, List<String> tunes, TuneFavorites favorites) {
        this.context = context;
        this.tunes = tunes;
        this.favorites = favorites;
    }

    @Override
    public int getCount() {
        return tunes.size();
    }

    @Override
    public Object getItem(int position) {
        return tunes.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.tune_list_item, parent, false);
            holder = new ViewHolder();
            holder.tuneNameText = convertView.findViewById(R.id.tuneNameText);
            holder.starButton = convertView.findViewById(R.id.starButton);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        final String tuneName = tunes.get(position);
        holder.tuneNameText.setText(tuneName);

        // Set star state
        updateStarButton(holder.starButton, tuneName, StarColor.STAR);

        // Set click listener for star
        holder.starButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                favorites.toggleFavorited(tuneName, StarColor.STAR);
                updateStarButton((Button) v, tuneName, StarColor.STAR);
            }
        });

        return convertView;
    }

    private void updateStarButton(Button button, String tuneName, StarColor color) {
        boolean isFavorited = favorites.isFavorited(tuneName, color);
        button.setText(isFavorited ? color.getFilledSymbol() : color.getEmptySymbol());
    }

    private static class ViewHolder {
        TextView tuneNameText;
        Button starButton;
    }
}